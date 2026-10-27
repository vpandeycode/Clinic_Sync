package org.clinicsync.edge.service;

import org.clinicsync.common.model.NodeRegistration;
import org.clinicsync.common.model.PullResponse;
import org.clinicsync.common.model.SyncAck;
import org.clinicsync.common.model.SyncBatch;
import org.clinicsync.common.model.ChangeRecord;
import org.clinicsync.common.sync.BackoffPolicy;
import org.clinicsync.common.sync.Sleeper;
import org.clinicsync.common.util.UuidV7Generator;
import org.clinicsync.edge.repository.EdgeResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/**
 * The edge sync agent: enrols the node, pushes journalled changes upstream, then pulls the central
 * feed downstream.
 *
 * <p>Protocol guarantees implemented here:</p>
 * <ul>
 *   <li><b>Push then pull:</b> local writes leave first so central sees the freshest state before
 *       we ask for what changed elsewhere.</li>
 *   <li><b>Ack-driven cursor:</b> {@code last_pushed_seq} only advances to the highest
 *       <em>contiguous</em> sequence central confirmed — never to what we merely sent.</li>
 *   <li><b>Idempotent pull:</b> pages are applied transactionally with the cursor, so a crash
 *       mid-sync replays cleanly without duplicating or skipping changes.</li>
 *   <li><b>Retry with exponential backoff + jitter:</b> transient network failures back off
 *       instead of hammering the central server; deterministic rejections (4xx) are not
 *       retried because they will never succeed.</li>
 * </ul>
 */
@Service
public class EdgeSyncClient {

    private static final Logger log = LoggerFactory.getLogger(EdgeSyncClient.class);

    private static final String CENTRAL_PEER = "central";

    private final EdgeResourceRepository repository;
    private final RestClient restClient;
    private final NodeKeyManager keyManager;
    private final String nodeId;
    private final String clinicScope;
    private final String centralUrl;
    private final Sleeper sleeper;
    private final BackoffPolicy backoff;
    private final int maxAttempts;
    private final int batchSize;
    private final int pullPageSize;
    private final int maxPagesPerSync;
    private final int maxPushesPerSync;

    @Autowired
    public EdgeSyncClient(
            EdgeResourceRepository repository,
            RestClient restClient,
            NodeKeyManager keyManager,
            @Value("${clinic.node-id}") String nodeId,
            @Value("${clinic.scope}") String clinicScope,
            @Value("${clinic.central-url}") String centralUrl,
            @Value("${sync.max-attempts:5}") int maxAttempts,
            @Value("${sync.batch-size:50}") int batchSize,
            @Value("${sync.pull-page-size:50}") int pullPageSize,
            @Value("${sync.max-pages-per-sync:200}") int maxPagesPerSync,
            @Value("${sync.max-pushes-per-sync:200}") int maxPushesPerSync,
            @Value("${sync.backoff-base-ms:200}") long backoffBaseMs,
            @Value("${sync.backoff-max-ms:5000}") long backoffMaxMs
    ) {
        this(repository, restClient, keyManager, nodeId, clinicScope, centralUrl, maxAttempts, batchSize,
                pullPageSize, maxPagesPerSync, maxPushesPerSync, backoffBaseMs, backoffMaxMs, Sleeper.REAL);
    }

    /** Test-friendly constructor with an injectable sleeper and backoff jitter source. */
    public EdgeSyncClient(
            EdgeResourceRepository repository,
            RestClient restClient,
            NodeKeyManager keyManager,
            String nodeId,
            String clinicScope,
            String centralUrl,
            int maxAttempts,
            int batchSize,
            int pullPageSize,
            int maxPagesPerSync,
            int maxPushesPerSync,
            long backoffBaseMs,
            long backoffMaxMs,
            Sleeper sleeper
    ) {
        this(repository, restClient, keyManager, nodeId, clinicScope, centralUrl, maxAttempts, batchSize,
                pullPageSize, maxPagesPerSync, maxPushesPerSync, backoffBaseMs, backoffMaxMs, sleeper, () -> 0.5d);
    }

    /** Compact constructor with sensible defaults, used by integration tests. */
    public EdgeSyncClient(
            EdgeResourceRepository repository,
            RestClient restClient,
            NodeKeyManager keyManager,
            String nodeId,
            String clinicScope,
            String centralUrl
    ) {
        this(repository, restClient, keyManager, nodeId, clinicScope, centralUrl,
                3, 50, 50, 100, 100, 20, 100, Sleeper.REAL);
    }

    private EdgeSyncClient(
            EdgeResourceRepository repository,
            RestClient restClient,
            NodeKeyManager keyManager,
            String nodeId,
            String clinicScope,
            String centralUrl,
            int maxAttempts,
            int batchSize,
            int pullPageSize,
            int maxPagesPerSync,
            int maxPushesPerSync,
            long backoffBaseMs,
            long backoffMaxMs,
            Sleeper sleeper,
            java.util.function.DoubleSupplier jitter
    ) {
        this.repository = repository;
        this.restClient = restClient;
        this.keyManager = keyManager;
        this.nodeId = nodeId;
        this.clinicScope = clinicScope;
        this.centralUrl = centralUrl;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.batchSize = Math.max(1, batchSize);
        this.pullPageSize = Math.max(1, pullPageSize);
        this.maxPagesPerSync = Math.max(1, maxPagesPerSync);
        this.maxPushesPerSync = Math.max(1, maxPushesPerSync);
        this.sleeper = sleeper;
        this.backoff = new BackoffPolicy(backoffBaseMs, backoffMaxMs, jitter);
    }

    /** Outcome of a full sync cycle, surfaced by the edge status API and asserted in tests. */
    public record SyncResult(String status, int batchesPushed, int changesPushed, int pagesPulled, int changesPulled) {}

    /**
     * Full cycle: enrol, push everything journalled, then pull everything new from central.
     */
    public SyncResult syncOnce() {
        registerNode();

        int batchesPushed = 0;
        int changesPushed = 0;
        long cursor = repository.getLastPushedSeq(CENTRAL_PEER);
        while (batchesPushed < maxPushesPerSync) {
            List<ChangeRecord> pending = repository.findPendingChanges(cursor, batchSize);
            if (pending.isEmpty()) {
                break;
            }
            SyncAck ack = pushBatch(pending);
            batchesPushed++;
            changesPushed += pending.size();
            long newCursor = Math.max(cursor, ack.highestContiguousOriginSeq());
            if (newCursor == cursor) {
                log.warn("Push of batch {} made no progress (ack cursor {}); stopping this cycle",
                        ack.batchId(), ack.highestContiguousOriginSeq());
                break;
            }
            cursor = newCursor;
            repository.updateLastPushedSeq(CENTRAL_PEER, newCursor);
        }

        int pagesPulled = 0;
        int changesPulled = 0;
        while (pagesPulled < maxPagesPerSync) {
            long since = repository.getLastPulledGlobalSeq(CENTRAL_PEER);
            PullResponse page = pullPage(since);
            repository.applyPullPage(page.changes(), page.nextGlobalSeq());
            pagesPulled++;
            changesPulled += page.changes().size();
            if (!page.hasMore()) {
                break;
            }
            if (page.nextGlobalSeq() <= since && page.changes().isEmpty()) {
                break;
            }
        }

        log.info("Sync cycle for {} complete: {} batch(es), {} pushed change(s), {} page(s), {} pulled change(s)",
                nodeId, batchesPushed, changesPushed, pagesPulled, changesPulled);

        return new SyncResult("OK", batchesPushed, changesPushed, pagesPulled, changesPulled);
    }

    /**
     * Pushes every journalled change upstream, one bounded batch at a time, and returns the ack of
     * the last batch (or an UP_TO_DATE ack when there was nothing to send).
     */
    public SyncAck pushPendingChanges() {
        registerNode();

        SyncAck lastAck = null;
        long cursor = repository.getLastPushedSeq(CENTRAL_PEER);

        for (int i = 0; i < maxPushesPerSync; i++) {
            List<ChangeRecord> pending = repository.findPendingChanges(cursor, batchSize);
            if (pending.isEmpty()) {
                return lastAck != null ? lastAck : new SyncAck("none", nodeId, cursor, 0, "UP_TO_DATE");
            }

            SyncAck ack = pushBatch(pending);
            lastAck = ack;

            if (ack.highestContiguousOriginSeq() <= cursor) {
                // No forward progress: a gap, a rejection already thrown, or a central that lost
                // data. Stop and let the caller/backoff decide; never spin.
                return ack;
            }

            cursor = ack.highestContiguousOriginSeq();
            repository.updateLastPushedSeq(CENTRAL_PEER, cursor);
        }

        return lastAck;
    }

    /** Pulls a single page of central changes into the local store. */
    public PullResponse pullOnce() {
        registerNode();
        long since = repository.getLastPulledGlobalSeq(CENTRAL_PEER);
        PullResponse page = pullPage(since);
        repository.applyPullPage(page.changes(), page.nextGlobalSeq());
        return page;
    }

    /** Registers (or refreshes) this node's public key and clinic scope at central. */
    public void registerNode() {
        NodeRegistration registration = new NodeRegistration(nodeId, keyManager.publicKeyBase64(), clinicScope);
        executeWithRetry("node registration", () -> restClient.post()
                .uri(centralUrl + "/api/v1/nodes/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(registration)
                .retrieve()
                .body(String.class));
    }

    private SyncAck pushBatch(List<ChangeRecord> pending) {
        String batchId = UuidV7Generator.generate().toString();
        SyncBatch batch = new SyncBatch(batchId, nodeId, clinicScope, pending);

        log.debug("Edge {} pushing batch {} with {} change(s)", nodeId, batchId, pending.size());

        SyncAck ack = executeWithRetry("push", () -> restClient.post()
                .uri(centralUrl + "/api/v1/sync/push")
                .contentType(MediaType.APPLICATION_JSON)
                .body(batch)
                .retrieve()
                .body(SyncAck.class));

        if (ack == null) {
            throw new IllegalStateException("Central returned an empty ack for batch " + batchId);
        }
        return ack;
    }

    private PullResponse pullPage(long since) {
        PullResponse page = executeWithRetry("pull", () -> restClient.get()
                .uri(centralUrl + "/api/v1/sync/pull?nodeId={nodeId}&since={since}&limit={limit}",
                        nodeId, since, pullPageSize)
                .retrieve()
                .body(PullResponse.class));

        if (page == null) {
            throw new IllegalStateException("Central returned an empty pull page since " + since);
        }
        return page;
    }

    /**
     * Retries transient failures (connection refused, timeouts, 5xx) with exponential backoff and
     * jitter. Deterministic 4xx rejections are propagated immediately: they are protocol errors
     * that retrying cannot fix.
     */
    private <T> T executeWithRetry(String operation, java.util.function.Supplier<T> action) {
        RuntimeException lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().is4xxClientError()) {
                    log.warn("Central rejected {} for node {} : HTTP {} {}",
                            operation, nodeId, e.getStatusCode().value(), e.getResponseBodyAsString());
                    throw e;
                }
                lastFailure = e;
            } catch (ResourceAccessException e) {
                lastFailure = e;
            }

            if (attempt < maxAttempts) {
                long delay = backoff.delayFor(attempt);
                log.info("Transient failure during {} (attempt {}/{}), retrying in {} ms",
                        operation, attempt, maxAttempts, delay);
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while backing off " + operation, ie);
                }
            }
        }

        throw new IllegalStateException(
                "Sync operation '" + operation + "' failed after " + maxAttempts + " attempts", lastFailure);
    }
}

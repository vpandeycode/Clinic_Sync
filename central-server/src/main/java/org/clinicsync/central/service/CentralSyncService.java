package org.clinicsync.central.service;

import org.clinicsync.central.repository.NodeRegistryRepository;
import org.clinicsync.central.repository.NodeRegistryRepository.NodeRecord;
import org.clinicsync.central.repository.NodeRegistryRepository.ScopeTags;
import org.clinicsync.common.exception.InvalidFhirException;
import org.clinicsync.common.exception.SyncRejectedException;
import org.clinicsync.common.fhir.FhirUtil;
import org.clinicsync.common.hlc.Hlc;
import org.clinicsync.common.model.ChangeRecord;
import org.clinicsync.common.model.PullResponse;
import org.clinicsync.common.model.PulledChange;
import org.clinicsync.common.model.SyncAck;
import org.clinicsync.common.model.SyncBatch;
import org.clinicsync.common.sync.VersionGraph;
import org.clinicsync.common.util.UuidV7Generator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Sync ingestion (push) and replication feed (pull) for the central hub.
 *
 * <p>Push semantics: batches arrive per edge node in dense {@code origin_seq} order. Every change
 * is validated (node enrolment, scope, HLC drift, FHIR structure) before it is written in one
 * transaction. Duplicates are recognised through {@code applied_changes} (idempotency), and a
 * missing sequence stops the batch at the last contiguous change so the edge can replay the gap —
 * the ACK only ever reports the contiguous prefix, which is the only thing the edge may advance
 * its cursor to.</p>
 *
 * <p>A change whose parents do not cover the current head set is a concurrent branch: both
 * versions are kept, {@code resource_head.has_conflict} is raised and a {@code conflict_case} is
 * opened for human review. Nothing clinical is ever resolved silently here.</p>
 */
@Service
public class CentralSyncService {

    private static final Logger log = LoggerFactory.getLogger(CentralSyncService.class);

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "Patient", "Encounter", "Observation", "AllergyIntolerance", "Consent", "AuditEvent"
    );

    private static final String CENTRAL_NODE_ID = "central";

    private final JdbcClient jdbcClient;
    private final NodeRegistryService nodeRegistryService;
    private final NodeRegistryRepository nodeRegistryRepository;
    private final long maxDriftMs;
    private final int defaultPullLimit;
    private final int maxPullLimit;

    public CentralSyncService(
            JdbcClient jdbcClient,
            NodeRegistryService nodeRegistryService,
            NodeRegistryRepository nodeRegistryRepository,
            @Value("${sync.max-clock-drift-ms:300000}") long maxDriftMs,
            @Value("${sync.pull-page-size:100}") int defaultPullLimit,
            @Value("${sync.max-pull-limit:500}") int maxPullLimit
    ) {
        this.jdbcClient = jdbcClient;
        this.nodeRegistryService = nodeRegistryService;
        this.nodeRegistryRepository = nodeRegistryRepository;
        this.maxDriftMs = maxDriftMs;
        this.defaultPullLimit = defaultPullLimit;
        this.maxPullLimit = maxPullLimit;
    }

    // ------------------------------------------------------------------
    // Push
    // ------------------------------------------------------------------

    @Transactional
    public SyncAck ingestBatch(SyncBatch batch) {
        if (batch == null || batch.originNodeId() == null || batch.changes() == null) {
            throw SyncRejectedException.invalidBatch("SyncBatch must declare an origin node and a change list");
        }

        String nodeId = batch.originNodeId();
        NodeRecord node = nodeRegistryService.requireActive(nodeId);

        if (batch.clinicScope() == null || !batch.clinicScope().equals(node.clinicScope())) {
            throw SyncRejectedException.scopeMismatch(nodeId, node.clinicScope(), String.valueOf(batch.clinicScope()));
        }

        List<ChangeRecord> changes = batch.changes().stream()
                .sorted(Comparator.comparingLong(ChangeRecord::originSeq))
                .toList();

        for (ChangeRecord change : changes) {
            if (!nodeId.equals(change.originNodeId())) {
                throw SyncRejectedException.invalidBatch(
                        "Change " + change.originSeq() + " declares origin '" + change.originNodeId()
                                + "' but batch origin is '" + nodeId + "'"
                );
            }
        }

        long expected = computeHighestContiguousSeq(nodeId) + 1;
        int appliedCount = 0;
        boolean gap = false;

        for (ChangeRecord change : changes) {
            if (change.originSeq() < expected) {
                verifyAlreadyApplied(nodeId, change);
                continue;
            }
            if (change.originSeq() > expected) {
                gap = true;
                log.warn("Gap detected for node {}: expected origin_seq {} but received {}. Stopping batch at {}",
                        nodeId, expected, change.originSeq(), expected - 1);
                break;
            }
            validateChange(nodeId, change);
            applyChange(nodeId, change);
            appliedCount++;
            expected++;
        }

        long highestContiguous = expected - 1;
        String status = gap ? "PARTIAL" : "APPLIED";

        log.info("Batch {} from {} processed: {} applied. Highest contiguous originSeq: {} ({})",
                batch.batchId(), nodeId, appliedCount, highestContiguous, status);

        return new SyncAck(batch.batchId(), nodeId, highestContiguous, appliedCount, status);
    }

    /**
     * Duplicate detection with an integrity check: a replayed sequence must carry the exact bytes
     * that were stored the first time, otherwise the payload does not match the record and the
     * push is rejected instead of silently ignored.
     */
    private void verifyAlreadyApplied(String nodeId, ChangeRecord change) {
        Optional<String> stored = jdbcClient.sql("""
                SELECT rv.content_json
                FROM applied_changes ac
                JOIN resource_version rv ON rv.resource_type = ac.resource_type
                                        AND rv.resource_id = ac.resource_id
                                        AND rv.version_id = ac.version_id
                WHERE ac.origin_node_id = :nodeId AND ac.origin_seq = :seq
                """)
                .param("nodeId", nodeId)
                .param("seq", change.originSeq())
                .query(String.class)
                .optional();

        if (stored.isPresent() && !stored.get().equals(change.contentJson())) {
            throw SyncRejectedException.tampered(
                    "Replayed change " + nodeId + ":" + change.originSeq() + " has different content than the applied record");
        }
    }

    private void validateChange(String nodeId, ChangeRecord change) {
        if (!SUPPORTED_TYPES.contains(change.resourceType())) {
            throw SyncRejectedException.invalidBatch("Unsupported resource type in sync batch: " + change.resourceType());
        }

        Hlc hlc;
        try {
            hlc = Hlc.parse(change.hlc());
        } catch (IllegalArgumentException e) {
            throw SyncRejectedException.invalidBatch("Invalid HLC for change " + change.originSeq() + ": " + e.getMessage());
        }

        long now = System.currentTimeMillis();
        if (hlc.l() - now > maxDriftMs) {
            throw SyncRejectedException.hlcDrift(nodeId, hlc.l(), now, maxDriftMs);
        }

        if (!change.isTombstone()) {
            try {
                FhirUtil.validateAndParse(change.resourceType(), change.contentJson());
            } catch (InvalidFhirException e) {
                throw SyncRejectedException.invalidBatch(
                        "Invalid FHIR payload for change " + change.originSeq() + ": " + e.getMessage());
            }
        }
    }

    private void applyChange(String nodeId, ChangeRecord change) {
        String type = change.resourceType();
        String id = change.resourceId();
        String versionId = change.versionId();
        Timestamp now = Timestamp.from(Instant.now());

        Optional<String> existing = jdbcClient.sql("""
                SELECT content_json FROM resource_version
                WHERE resource_type = :type AND resource_id = :id AND version_id = :versionId
                """)
                .param("type", type)
                .param("id", id)
                .param("versionId", versionId)
                .query(String.class)
                .optional();

        if (existing.isPresent()) {
            if (!existing.get().equals(change.contentJson())) {
                throw SyncRejectedException.tampered(
                        "Version " + type + "/" + id + "/" + versionId + " already exists with different content");
            }
            recordApplied(nodeId, change, now);
            return;
        }

        insertResourceVersion(type, id, versionId, change, now);

        Optional<String> currentHead = findHead(type, id);
        if (currentHead.isEmpty()) {
            insertHead(type, id, versionId, false, now);
        } else {
            VersionGraph.HeadUpdate update = VersionGraph.apply(currentHead.get(), change.parentVersionIds(), versionId);
            updateHead(type, id, update.headVersionIds(), update.hasConflict(), now);
            if (update.hasConflict()) {
                openOrUpdateConflictCase(type, id, update.headVersionIds());
                log.warn("Concurrent branch detected for {}/{}: heads now [{}]", type, id, update.headVersionIds());
            }
        }

        recordApplied(nodeId, change, now);
    }

    private void insertResourceVersion(String type, String id, String versionId, ChangeRecord change, Timestamp now) {
        jdbcClient.sql("""
                INSERT INTO resource_version (
                    resource_type, resource_id, version_id, content_json,
                    parent_version_ids, origin_node_id, origin_seq, hlc, author, is_tombstone, created_at
                ) VALUES (
                    :type, :id, :versionId, :json,
                    :parents, :originNodeId, :originSeq, :hlc, :author, :tombstone, :createdAt
                )
                """)
                .param("type", type)
                .param("id", id)
                .param("versionId", versionId)
                .param("json", change.contentJson())
                .param("parents", change.parentVersionIds())
                .param("originNodeId", change.originNodeId())
                .param("originSeq", change.originSeq())
                .param("hlc", change.hlc())
                .param("author", change.author())
                .param("tombstone", change.isTombstone())
                .param("createdAt", now)
                .update();
    }

    private Optional<String> findHead(String type, String id) {
        return jdbcClient.sql("""
                SELECT head_version_ids FROM resource_head
                WHERE resource_type = :type AND resource_id = :id
                """)
                .param("type", type)
                .param("id", id)
                .query(String.class)
                .optional();
    }

    private void insertHead(String type, String id, String headVersionId, boolean hasConflict, Timestamp now) {
        jdbcClient.sql("""
                INSERT INTO resource_head (resource_type, resource_id, head_version_ids, has_conflict, updated_at)
                VALUES (:type, :id, :head, :hasConflict, :updatedAt)
                """)
                .param("type", type)
                .param("id", id)
                .param("head", headVersionId)
                .param("hasConflict", hasConflict)
                .param("updatedAt", now)
                .update();
    }

    private void updateHead(String type, String id, String headVersionIds, boolean hasConflict, Timestamp now) {
        jdbcClient.sql("""
                UPDATE resource_head
                SET head_version_ids = :head, has_conflict = :hasConflict, updated_at = :updatedAt
                WHERE resource_type = :type AND resource_id = :id
                """)
                .param("head", headVersionIds)
                .param("hasConflict", hasConflict)
                .param("updatedAt", now)
                .param("type", type)
                .param("id", id)
                .update();
    }

    private void recordApplied(String nodeId, ChangeRecord change, Timestamp now) {
        boolean present = jdbcClient.sql("""
                SELECT COUNT(1) FROM applied_changes
                WHERE origin_node_id = :nodeId AND origin_seq = :seq
                """)
                .param("nodeId", nodeId)
                .param("seq", change.originSeq())
                .query(Integer.class)
                .single() > 0;

        if (present) {
            return;
        }

        jdbcClient.sql("""
                INSERT INTO applied_changes (origin_node_id, origin_seq, resource_type, resource_id, version_id, applied_at)
                VALUES (:nodeId, :seq, :type, :id, :versionId, :appliedAt)
                """)
                .param("nodeId", nodeId)
                .param("seq", change.originSeq())
                .param("type", change.resourceType())
                .param("id", change.resourceId())
                .param("versionId", change.versionId())
                .param("appliedAt", now)
                .update();
    }

    private void openOrUpdateConflictCase(String type, String id, String headVersionIds) {
        boolean openCaseExists = jdbcClient.sql("""
                SELECT COUNT(1) FROM conflict_case
                WHERE resource_type = :type AND resource_id = :id AND status = 'OPEN'
                """)
                .param("type", type)
                .param("id", id)
                .query(Integer.class)
                .single() > 0;

        Timestamp now = Timestamp.from(Instant.now());

        if (openCaseExists) {
            jdbcClient.sql("""
                    UPDATE conflict_case
                    SET conflicting_version_ids = :heads
                    WHERE resource_type = :type AND resource_id = :id AND status = 'OPEN'
                    """)
                    .param("heads", headVersionIds)
                    .param("type", type)
                    .param("id", id)
                    .update();
            return;
        }

        jdbcClient.sql("""
                INSERT INTO conflict_case (case_id, resource_type, resource_id, conflicting_version_ids, status, created_at)
                VALUES (:caseId, :type, :id, :heads, 'OPEN', :createdAt)
                """)
                .param("caseId", UuidV7Generator.generate().toString())
                .param("type", type)
                .param("id", id)
                .param("heads", headVersionIds)
                .param("createdAt", now)
                .update();
    }

    /** Highest origin_seq for a node applied without gaps (sequences start at 1). */
    public long computeHighestContiguousSeq(String nodeId) {
        List<Long> seqs = jdbcClient.sql("""
                SELECT origin_seq FROM applied_changes
                WHERE origin_node_id = :nodeId
                ORDER BY origin_seq ASC
                """)
                .param("nodeId", nodeId)
                .query(Long.class)
                .list();

        long expected = 1L;
        for (Long seq : seqs) {
            if (seq == expected) {
                expected++;
            } else if (seq > expected) {
                break;
            }
        }
        return expected - 1;
    }

    // ------------------------------------------------------------------
    // Pull
    // ------------------------------------------------------------------

    /**
     * Returns in-scope changes after {@code since}, ordered by the central timeline cursor.
     *
     * <p>Visibility rule: a change is delivered when it originated on the requester itself, on the
     * central server, or on a node that shares at least one clinic scope tag with the requester.
     * The returned cursor is the highest {@code global_seq} <em>scanned</em> — including rows
     * filtered out — so the edge makes progress on every page and never re-reads or skips a row.</p>
     */
    public PullResponse pull(String nodeId, long since, Integer limitParam) {
        NodeRecord node = nodeRegistryService.requireActive(nodeId);
        int limit = limitParam == null ? defaultPullLimit : Math.min(Math.max(limitParam, 1), maxPullLimit);

        Set<String> requesterScopes = ScopeTags.parse(node.clinicScope());
        Map<String, Set<String>> originScopes = nodeRegistryRepository.scopeIndex();

        List<PullRow> rows = jdbcClient.sql("""
                SELECT ac.global_seq, ac.origin_node_id, ac.origin_seq,
                       ac.resource_type, ac.resource_id, ac.version_id,
                       rv.content_json, rv.parent_version_ids, rv.hlc, rv.author, rv.is_tombstone
                FROM applied_changes ac
                LEFT JOIN resource_version rv
                       ON rv.resource_type = ac.resource_type
                      AND rv.resource_id = ac.resource_id
                      AND rv.version_id = ac.version_id
                WHERE ac.global_seq > :since
                ORDER BY ac.global_seq ASC
                LIMIT :limit
                """)
                .param("since", since)
                .param("limit", limit)
                .query((rs, rowNum) -> new PullRow(
                        rs.getLong("global_seq"),
                        rs.getString("origin_node_id"),
                        rs.getLong("origin_seq"),
                        rs.getString("resource_type"),
                        rs.getString("resource_id"),
                        rs.getString("version_id"),
                        rs.getString("content_json"),
                        rs.getString("parent_version_ids"),
                        rs.getString("hlc"),
                        rs.getString("author"),
                        rs.getBoolean("is_tombstone")
                ))
                .list();

        List<PulledChange> visible = new ArrayList<>();
        long nextGlobalSeq = since;

        for (PullRow row : rows) {
            nextGlobalSeq = row.globalSeq();
            if (row.contentJson() == null) {
                // applied_changes and resource_version are written in one transaction, so this can
                // only mean storage corruption. Fail loudly instead of silently dropping a change.
                throw new IllegalStateException("Applied change without matching resource_version row: global_seq="
                        + row.globalSeq() + " " + row.resourceType() + "/" + row.resourceId() + "/" + row.versionId());
            }
            if (isVisible(row.originNodeId(), nodeId, requesterScopes, originScopes)) {
                visible.add(row.toChange());
            }
        }

        boolean hasMore = rows.size() >= limit;
        return new PullResponse(nodeId, visible, nextGlobalSeq, hasMore);
    }

    private static boolean isVisible(String originNodeId, String requesterId, Set<String> requesterScopes,
                                     Map<String, Set<String>> originScopes) {
        if (CENTRAL_NODE_ID.equals(originNodeId) || requesterId.equals(originNodeId)) {
            return true;
        }
        Set<String> origin = originScopes.get(originNodeId);
        return origin != null && !Collections.disjoint(origin, requesterScopes);
    }

    private record PullRow(
            long globalSeq,
            String originNodeId,
            long originSeq,
            String resourceType,
            String resourceId,
            String versionId,
            String contentJson,
            String parentVersionIds,
            String hlc,
            String author,
            boolean isTombstone
    ) {
        PulledChange toChange() {
            return new PulledChange(globalSeq, originSeq, originNodeId, resourceType, resourceId, versionId,
                    contentJson, parentVersionIds, hlc, author, isTombstone);
        }
    }
}

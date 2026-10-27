package org.clinicsync.edge.service;

import org.clinicsync.common.model.ChangeRecord;
import org.clinicsync.common.model.SyncAck;
import org.clinicsync.common.model.SyncBatch;
import org.clinicsync.edge.repository.EdgeResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

@Service
public class EdgeSyncClient {

    private static final Logger log = LoggerFactory.getLogger(EdgeSyncClient.class);

    private final EdgeResourceRepository repository;
    private final RestClient restClient;
    private final String nodeId;
    private final String clinicScope;
    private final String centralUrl;

    public EdgeSyncClient(
            EdgeResourceRepository repository,
            RestClient restClient,
            @Value("${clinic.node-id}") String nodeId,
            @Value("${clinic.scope}") String clinicScope,
            @Value("${clinic.central-url}") String centralUrl
    ) {
        this.repository = repository;
        this.restClient = restClient;
        this.nodeId = nodeId;
        this.clinicScope = clinicScope;
        this.centralUrl = centralUrl;
    }

    public SyncAck pushPendingChanges() {
        long lastPushedSeq = repository.getLastPushedSeq("central");
        List<ChangeRecord> pending = repository.findPendingChanges(lastPushedSeq, 50);

        if (pending.isEmpty()) {
            return new SyncAck("none", nodeId, lastPushedSeq, 0, "UP_TO_DATE");
        }

        String batchId = UUID.randomUUID().toString();
        SyncBatch batch = new SyncBatch(batchId, nodeId, clinicScope, pending);

        log.info("Edge node {} pushing batch {} with {} changes to {}", nodeId, batchId, pending.size(), centralUrl);

        SyncAck ack = restClient.post()
                .uri(centralUrl + "/api/v1/sync/push")
                .contentType(MediaType.APPLICATION_JSON)
                .body(batch)
                .retrieve()
                .body(SyncAck.class);

        if (ack != null && ack.highestContiguousOriginSeq() > lastPushedSeq) {
            repository.updateLastPushedSeq("central", ack.highestContiguousOriginSeq());
            log.info("Batch {} applied successfully by central. Advancing edge cursor to {}",
                    batchId, ack.highestContiguousOriginSeq());
        }

        return ack;
    }
}

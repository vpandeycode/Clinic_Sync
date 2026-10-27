package org.clinicsync.central.service;

import org.clinicsync.common.model.ChangeRecord;
import org.clinicsync.common.model.SyncAck;
import org.clinicsync.common.model.SyncBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class CentralSyncService {

    private static final Logger log = LoggerFactory.getLogger(CentralSyncService.class);

    private final JdbcClient jdbcClient;

    public CentralSyncService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public SyncAck ingestBatch(SyncBatch batch) {
        if (batch == null || batch.changes() == null) {
            throw new IllegalArgumentException("SyncBatch and changes must not be null");
        }

        String nodeId = batch.originNodeId();
        List<ChangeRecord> changes = batch.changes().stream()
                .sorted(Comparator.comparingLong(ChangeRecord::originSeq))
                .toList();

        int appliedCount = 0;

        for (ChangeRecord change : changes) {
            // Check if already applied (Idempotency check)
            boolean alreadyApplied = jdbcClient.sql("""
                    SELECT COUNT(1) FROM applied_changes
                    WHERE origin_node_id = :nodeId AND origin_seq = :seq
                    """)
                    .param("nodeId", nodeId)
                    .param("seq", change.originSeq())
                    .query(Integer.class)
                    .single() > 0;

            if (alreadyApplied) {
                log.debug("Change {}:{} already applied, skipping", nodeId, change.originSeq());
                continue;
            }

            Timestamp now = Timestamp.from(Instant.now());

            // 1. Insert into resource_version
            jdbcClient.sql("""
                    INSERT INTO resource_version (
                        resource_type, resource_id, version_id, content_json,
                        parent_version_ids, origin_node_id, origin_seq, hlc, author, is_tombstone, created_at
                    ) VALUES (
                        :type, :id, :ver, :json,
                        :parents, :nodeId, :seq, :hlc, :author, :tombstone, :createdAt
                    )
                    """)
                    .param("type", change.resourceType())
                    .param("id", change.resourceId())
                    .param("ver", change.versionId())
                    .param("json", change.contentJson())
                    .param("parents", change.parentVersionIds())
                    .param("nodeId", nodeId)
                    .param("seq", change.originSeq())
                    .param("hlc", change.hlc())
                    .param("author", change.author())
                    .param("tombstone", change.isTombstone())
                    .param("createdAt", now)
                    .update();

            // 2. Portable update/insert for resource_head
            int updated = jdbcClient.sql("""
                    UPDATE resource_head
                    SET head_version_ids = :headVer, has_conflict = FALSE, updated_at = :updatedAt
                    WHERE resource_type = :type AND resource_id = :id
                    """)
                    .param("headVer", change.versionId())
                    .param("updatedAt", now)
                    .param("type", change.resourceType())
                    .param("id", change.resourceId())
                    .update();

            if (updated == 0) {
                jdbcClient.sql("""
                        INSERT INTO resource_head (resource_type, resource_id, head_version_ids, has_conflict, updated_at)
                        VALUES (:type, :id, :headVer, FALSE, :updatedAt)
                        """)
                        .param("type", change.resourceType())
                        .param("id", change.resourceId())
                        .param("headVer", change.versionId())
                        .param("updatedAt", now)
                        .update();
            }

            // 3. Record in applied_changes
            jdbcClient.sql("""
                    INSERT INTO applied_changes (origin_node_id, origin_seq, resource_type, resource_id, version_id, applied_at)
                    VALUES (:nodeId, :seq, :type, :id, :ver, :appliedAt)
                    """)
                    .param("nodeId", nodeId)
                    .param("seq", change.originSeq())
                    .param("type", change.resourceType())
                    .param("id", change.resourceId())
                    .param("ver", change.versionId())
                    .param("appliedAt", now)
                    .update();

            appliedCount++;
        }

        long highestContiguousSeq = computeHighestContiguousSeq(nodeId);

        log.info("Batch {} from {} processed: {} applied. Highest contiguous originSeq: {}",
                batch.batchId(), nodeId, appliedCount, highestContiguousSeq);

        return new SyncAck(batch.batchId(), nodeId, highestContiguousSeq, appliedCount, "APPLIED");
    }

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

    public Optional<String> getResourceHeadContent(String resourceType, String resourceId) {
        return jdbcClient.sql("""
                SELECT rv.content_json
                FROM resource_head rh
                JOIN resource_version rv ON rh.resource_type = rv.resource_type
                                        AND rh.resource_id = rv.resource_id
                                        AND rh.head_version_ids = rv.version_id
                WHERE rh.resource_type = :type AND rh.resource_id = :id AND rv.is_tombstone = FALSE
                """)
                .param("type", resourceType)
                .param("id", resourceId)
                .query(String.class)
                .optional();
    }
}

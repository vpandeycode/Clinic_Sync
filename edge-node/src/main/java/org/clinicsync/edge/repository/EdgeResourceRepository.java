package org.clinicsync.edge.repository;

import org.clinicsync.common.model.ChangeRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class EdgeResourceRepository {

    private final JdbcClient jdbcClient;

    public EdgeResourceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public long saveLocalResource(
            String resourceType,
            String resourceId,
            String versionId,
            String contentJson,
            String parentVersionIds,
            String originNodeId,
            String hlc,
            String author,
            boolean isTombstone
    ) {
        Timestamp now = Timestamp.from(Instant.now());

        // 1. Insert into change_log to get the monotonic origin_seq
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                INSERT INTO change_log (resource_type, resource_id, version_id, created_at)
                VALUES (:type, :id, :ver, :createdAt)
                """)
                .param("type", resourceType)
                .param("id", resourceId)
                .param("ver", versionId)
                .param("createdAt", now)
                .update(keyHolder);

        long originSeq = ((Number) keyHolder.getKeys().get("seq")).longValue();

        // 2. Insert into resource_version with origin_seq
        jdbcClient.sql("""
                INSERT INTO resource_version (
                    resource_type, resource_id, version_id, content_json,
                    parent_version_ids, origin_node_id, origin_seq, hlc, author, is_tombstone, created_at
                ) VALUES (
                    :type, :id, :ver, :json,
                    :parents, :nodeId, :seq, :hlc, :author, :tombstone, :createdAt
                )
                """)
                .param("type", resourceType)
                .param("id", resourceId)
                .param("ver", versionId)
                .param("json", contentJson)
                .param("parents", parentVersionIds)
                .param("nodeId", originNodeId)
                .param("seq", originSeq)
                .param("hlc", hlc)
                .param("author", author)
                .param("tombstone", isTombstone)
                .param("createdAt", now)
                .update();

        // 3. Portable update/insert for resource_head
        int updated = jdbcClient.sql("""
                UPDATE resource_head
                SET head_version_ids = :headVer, has_conflict = FALSE, updated_at = :updatedAt
                WHERE resource_type = :type AND resource_id = :id
                """)
                .param("headVer", versionId)
                .param("updatedAt", now)
                .param("type", resourceType)
                .param("id", resourceId)
                .update();

        if (updated == 0) {
            jdbcClient.sql("""
                    INSERT INTO resource_head (resource_type, resource_id, head_version_ids, has_conflict, updated_at)
                    VALUES (:type, :id, :headVer, FALSE, :updatedAt)
                    """)
                    .param("type", resourceType)
                    .param("id", resourceId)
                    .param("headVer", versionId)
                    .param("updatedAt", now)
                    .update();
        }

        return originSeq;
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

    public List<ChangeRecord> findPendingChanges(long lastPushedSeq, int limit) {
        return jdbcClient.sql("""
                SELECT cl.seq, rv.resource_type, rv.resource_id, rv.version_id,
                       rv.content_json, rv.parent_version_ids, rv.origin_node_id,
                       rv.hlc, rv.author, rv.is_tombstone
                FROM change_log cl
                JOIN resource_version rv ON cl.resource_type = rv.resource_type
                                        AND cl.resource_id = rv.resource_id
                                        AND cl.version_id = rv.version_id
                WHERE cl.seq > :lastPushedSeq
                ORDER BY cl.seq ASC
                FETCH FIRST :limit ROWS ONLY
                """)
                .param("lastPushedSeq", lastPushedSeq)
                .param("limit", limit)
                .query((rs, rowNum) -> new ChangeRecord(
                        rs.getLong("seq"),
                        rs.getString("resource_type"),
                        rs.getString("resource_id"),
                        rs.getString("version_id"),
                        rs.getString("content_json"),
                        rs.getString("parent_version_ids"),
                        rs.getString("origin_node_id"),
                        rs.getString("hlc"),
                        rs.getString("author"),
                        rs.getBoolean("is_tombstone")
                ))
                .list();
    }

    public long getLastPushedSeq(String peer) {
        return jdbcClient.sql("""
                SELECT last_pushed_seq FROM sync_state WHERE peer = :peer
                """)
                .param("peer", peer)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    @Transactional
    public void updateLastPushedSeq(String peer, long seq) {
        Timestamp now = Timestamp.from(Instant.now());
        int updated = jdbcClient.sql("""
                UPDATE sync_state
                SET last_pushed_seq = :seq, last_sync_success = :successTime
                WHERE peer = :peer
                """)
                .param("seq", seq)
                .param("successTime", now)
                .param("peer", peer)
                .update();

        if (updated == 0) {
            jdbcClient.sql("""
                    INSERT INTO sync_state (peer, last_pushed_seq, last_pulled_global_seq, last_sync_success)
                    VALUES (:peer, :seq, 0, :successTime)
                    """)
                    .param("peer", peer)
                    .param("seq", seq)
                    .param("successTime", now)
                    .update();
        }
    }
}

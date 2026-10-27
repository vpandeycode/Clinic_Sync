package org.clinicsync.edge.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.clinicsync.common.exception.OptimisticLockException;
import org.clinicsync.common.exception.ResourceGoneException;
import org.clinicsync.common.exception.ResourceNotFoundException;
import org.clinicsync.common.fhir.FhirUtil;
import org.clinicsync.common.model.ChangeRecord;
import org.clinicsync.common.model.ResourceVersionRecord;
import org.clinicsync.common.util.UuidV7Generator;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record HeadInfo(String headVersionId, boolean hasConflict, boolean isTombstone) {}

    public EdgeResourceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public ResourceVersionRecord createResource(
            String resourceType,
            String jsonContent,
            String originNodeId,
            String author
    ) {
        FhirUtil.validateAndParse(resourceType, jsonContent);

        String resourceId;
        String finalJson;
        try {
            JsonNode root = objectMapper.readTree(jsonContent);
            if (root.hasNonNull("id")) {
                resourceId = root.get("id").asText();
            } else {
                resourceId = UuidV7Generator.generate().toString();
                ((ObjectNode) root).put("id", resourceId);
            }
            finalJson = objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Error processing JSON id", e);
        }

        String versionId = "1";
        String hlc = Instant.now().toEpochMilli() + ":0:" + originNodeId;
        Timestamp now = Timestamp.from(Instant.now());

        // 1. Insert into change_log
        long originSeq = recordChangeLog(resourceType, resourceId, versionId, now);

        // 2. Insert into resource_version
        insertResourceVersion(resourceType, resourceId, versionId, finalJson, null, originNodeId, originSeq, hlc, author, false, now);

        // 3. Update resource_head
        upsertResourceHead(resourceType, resourceId, versionId, false, now);

        return new ResourceVersionRecord(
                resourceType, resourceId, versionId, finalJson, null,
                originNodeId, originSeq, hlc, author, false, now.toInstant()
        );
    }

    @Transactional
    public ResourceVersionRecord updateResource(
            String resourceType,
            String resourceId,
            String ifMatchVersion,
            String jsonContent,
            String originNodeId,
            String author
    ) {
        FhirUtil.validateAndParse(resourceType, jsonContent);

        HeadInfo head = getHeadInfo(resourceType, resourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Resource " + resourceType + "/" + resourceId + " does not exist"));

        if (ifMatchVersion != null) {
            String expected = cleanEtag(ifMatchVersion);
            if (!head.headVersionId().equals(expected)) {
                throw new OptimisticLockException(
                        "Precondition Failed: Current version is " + head.headVersionId() + ", but If-Match was " + ifMatchVersion
                );
            }
        }

        int currentVerNum = parseVersionNumber(head.headVersionId());
        String nextVersion = String.valueOf(currentVerNum + 1);
        String hlc = Instant.now().toEpochMilli() + ":0:" + originNodeId;
        Timestamp now = Timestamp.from(Instant.now());

        // Ensure id is set in JSON
        String finalJson = ensureIdInJson(jsonContent, resourceId);

        // 1. change_log
        long originSeq = recordChangeLog(resourceType, resourceId, nextVersion, now);

        // 2. resource_version (parent is previous head)
        insertResourceVersion(resourceType, resourceId, nextVersion, finalJson, head.headVersionId(), originNodeId, originSeq, hlc, author, false, now);

        // 3. resource_head
        upsertResourceHead(resourceType, resourceId, nextVersion, false, now);

        return new ResourceVersionRecord(
                resourceType, resourceId, nextVersion, finalJson, head.headVersionId(),
                originNodeId, originSeq, hlc, author, false, now.toInstant()
        );
    }

    public ResourceVersionRecord readResource(String resourceType, String resourceId) {
        Optional<ResourceVersionRecord> recordOpt = jdbcClient.sql("""
                SELECT rv.resource_type, rv.resource_id, rv.version_id, rv.content_json,
                       rv.parent_version_ids, rv.origin_node_id, rv.origin_seq,
                       rv.hlc, rv.author, rv.is_tombstone, rv.created_at
                FROM resource_head rh
                JOIN resource_version rv ON rh.resource_type = rv.resource_type
                                        AND rh.resource_id = rv.resource_id
                                        AND rh.head_version_ids = rv.version_id
                WHERE rh.resource_type = :type AND rh.resource_id = :id
                """)
                .param("type", resourceType)
                .param("id", resourceId)
                .query((rs, rowNum) -> new ResourceVersionRecord(
                        rs.getString("resource_type"),
                        rs.getString("resource_id"),
                        rs.getString("version_id"),
                        rs.getString("content_json"),
                        rs.getString("parent_version_ids"),
                        rs.getString("origin_node_id"),
                        rs.getLong("origin_seq"),
                        rs.getString("hlc"),
                        rs.getString("author"),
                        rs.getBoolean("is_tombstone"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .optional();

        ResourceVersionRecord record = recordOpt.orElseThrow(() ->
                new ResourceNotFoundException("Resource " + resourceType + "/" + resourceId + " not found"));

        if (record.isTombstone()) {
            throw new ResourceGoneException("Resource " + resourceType + "/" + resourceId + " has been deleted");
        }

        return record;
    }

    public List<ResourceVersionRecord> getHistory(String resourceType, String resourceId) {
        List<ResourceVersionRecord> list = jdbcClient.sql("""
                SELECT resource_type, resource_id, version_id, content_json,
                       parent_version_ids, origin_node_id, origin_seq,
                       hlc, author, is_tombstone, created_at
                FROM resource_version
                WHERE resource_type = :type AND resource_id = :id
                ORDER BY created_at ASC, origin_seq ASC, version_id ASC
                """)
                .param("type", resourceType)
                .param("id", resourceId)
                .query((rs, rowNum) -> new ResourceVersionRecord(
                        rs.getString("resource_type"),
                        rs.getString("resource_id"),
                        rs.getString("version_id"),
                        rs.getString("content_json"),
                        rs.getString("parent_version_ids"),
                        rs.getString("origin_node_id"),
                        rs.getLong("origin_seq"),
                        rs.getString("hlc"),
                        rs.getString("author"),
                        rs.getBoolean("is_tombstone"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();

        if (list.isEmpty()) {
            throw new ResourceNotFoundException("Resource " + resourceType + "/" + resourceId + " not found");
        }

        return list;
    }

    @Transactional
    public ResourceVersionRecord deleteResource(
            String resourceType,
            String resourceId,
            String ifMatchVersion,
            String originNodeId,
            String author
    ) {
        HeadInfo head = getHeadInfo(resourceType, resourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Resource " + resourceType + "/" + resourceId + " does not exist"));

        if (head.isTombstone()) {
            throw new ResourceGoneException("Resource " + resourceType + "/" + resourceId + " is already deleted");
        }

        if (ifMatchVersion != null) {
            String expected = cleanEtag(ifMatchVersion);
            if (!head.headVersionId().equals(expected)) {
                throw new OptimisticLockException(
                        "Precondition Failed: Current version is " + head.headVersionId() + ", but If-Match was " + ifMatchVersion
                );
            }
        }

        int currentVerNum = parseVersionNumber(head.headVersionId());
        String nextVersion = String.valueOf(currentVerNum + 1);
        String hlc = Instant.now().toEpochMilli() + ":0:" + originNodeId;
        Timestamp now = Timestamp.from(Instant.now());
        String tombstoneJson = "{\"resourceType\":\"" + resourceType + "\",\"id\":\"" + resourceId + "\",\"meta\":{\"isTombstone\":true}}";

        // 1. change_log
        long originSeq = recordChangeLog(resourceType, resourceId, nextVersion, now);

        // 2. resource_version with is_tombstone = true
        insertResourceVersion(resourceType, resourceId, nextVersion, tombstoneJson, head.headVersionId(), originNodeId, originSeq, hlc, author, true, now);

        // 3. update resource_head
        upsertResourceHead(resourceType, resourceId, nextVersion, false, now);

        return new ResourceVersionRecord(
                resourceType, resourceId, nextVersion, tombstoneJson, head.headVersionId(),
                originNodeId, originSeq, hlc, author, true, now.toInstant()
        );
    }

    private Optional<HeadInfo> getHeadInfo(String resourceType, String resourceId) {
        return jdbcClient.sql("""
                SELECT rh.head_version_ids, rh.has_conflict, rv.is_tombstone
                FROM resource_head rh
                LEFT JOIN resource_version rv ON rh.resource_type = rv.resource_type
                                             AND rh.resource_id = rv.resource_id
                                             AND rh.head_version_ids = rv.version_id
                WHERE rh.resource_type = :type AND rh.resource_id = :id
                """)
                .param("type", resourceType)
                .param("id", resourceId)
                .query((rs, rowNum) -> new HeadInfo(
                        rs.getString("head_version_ids"),
                        rs.getBoolean("has_conflict"),
                        rs.getBoolean("is_tombstone")
                ))
                .optional();
    }

    private long recordChangeLog(String resourceType, String resourceId, String versionId, Timestamp now) {
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
        return ((Number) keyHolder.getKeys().get("seq")).longValue();
    }

    private void insertResourceVersion(
            String type, String id, String ver, String json, String parents,
            String nodeId, long seq, String hlc, String author, boolean isTombstone, Timestamp now
    ) {
        jdbcClient.sql("""
                INSERT INTO resource_version (
                    resource_type, resource_id, version_id, content_json,
                    parent_version_ids, origin_node_id, origin_seq, hlc, author, is_tombstone, created_at
                ) VALUES (
                    :type, :id, :ver, :json,
                    :parents, :nodeId, :seq, :hlc, :author, :tombstone, :createdAt
                )
                """)
                .param("type", type)
                .param("id", id)
                .param("ver", ver)
                .param("json", json)
                .param("parents", parents)
                .param("nodeId", nodeId)
                .param("seq", seq)
                .param("hlc", hlc)
                .param("author", author)
                .param("tombstone", isTombstone)
                .param("createdAt", now)
                .update();
    }

    private void upsertResourceHead(String type, String id, String headVer, boolean hasConflict, Timestamp now) {
        int updated = jdbcClient.sql("""
                UPDATE resource_head
                SET head_version_ids = :headVer, has_conflict = :hasConflict, updated_at = :updatedAt
                WHERE resource_type = :type AND resource_id = :id
                """)
                .param("headVer", headVer)
                .param("hasConflict", hasConflict)
                .param("updatedAt", now)
                .param("type", type)
                .param("id", id)
                .update();

        if (updated == 0) {
            jdbcClient.sql("""
                    INSERT INTO resource_head (resource_type, resource_id, head_version_ids, has_conflict, updated_at)
                    VALUES (:type, :id, :headVer, :hasConflict, :updatedAt)
                    """)
                    .param("type", type)
                    .param("id", id)
                    .param("headVer", headVer)
                    .param("hasConflict", hasConflict)
                    .param("updatedAt", now)
                    .update();
        }
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

    private static String cleanEtag(String etag) {
        if (etag == null) return null;
        String s = etag.trim();
        if (s.startsWith("W/")) s = s.substring(2);
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static int parseVersionNumber(String v) {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private String ensureIdInJson(String json, String id) {
        try {
            JsonNode root = objectMapper.readTree(json);
            ((ObjectNode) root).put("id", id);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return json;
        }
    }
}

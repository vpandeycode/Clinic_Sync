package org.clinicsync.central.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.clinicsync.common.exception.OptimisticLockException;
import org.clinicsync.common.exception.ResourceGoneException;
import org.clinicsync.common.exception.ResourceNotFoundException;
import org.clinicsync.common.fhir.FhirUtil;
import org.clinicsync.common.model.ResourceVersionRecord;
import org.clinicsync.common.util.UuidV7Generator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class CentralResourceRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record HeadInfo(String headVersionId, boolean hasConflict, boolean isTombstone) {}

    public CentralResourceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public ResourceVersionRecord createResource(
            String resourceType,
            String jsonContent,
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
        String originNodeId = "central";
        long originSeq = 0L; // Local direct central writes
        String hlc = Instant.now().toEpochMilli() + ":0:central";
        Timestamp now = Timestamp.from(Instant.now());

        insertResourceVersion(resourceType, resourceId, versionId, finalJson, null, originNodeId, originSeq, hlc, author, false, now);
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
        String originNodeId = "central";
        long originSeq = 0L;
        String hlc = Instant.now().toEpochMilli() + ":0:central";
        Timestamp now = Timestamp.from(Instant.now());

        String finalJson = ensureIdInJson(jsonContent, resourceId);

        insertResourceVersion(resourceType, resourceId, nextVersion, finalJson, head.headVersionId(), originNodeId, originSeq, hlc, author, false, now);
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
        String originNodeId = "central";
        long originSeq = 0L;
        String hlc = Instant.now().toEpochMilli() + ":0:central";
        Timestamp now = Timestamp.from(Instant.now());
        String tombstoneJson = "{\"resourceType\":\"" + resourceType + "\",\"id\":\"" + resourceId + "\",\"meta\":{\"isTombstone\":true}}";

        insertResourceVersion(resourceType, resourceId, nextVersion, tombstoneJson, head.headVersionId(), originNodeId, originSeq, hlc, author, true, now);
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

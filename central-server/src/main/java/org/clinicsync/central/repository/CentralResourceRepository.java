package org.clinicsync.central.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.clinicsync.common.exception.OptimisticLockException;
import org.clinicsync.common.exception.ResourceGoneException;
import org.clinicsync.common.exception.ResourceNotFoundException;
import org.clinicsync.common.fhir.FhirUtil;
import org.clinicsync.common.hlc.Hlc;
import org.clinicsync.common.hlc.HybridLogicalClock;
import org.clinicsync.common.model.ResourceVersionRecord;
import org.clinicsync.common.sync.VersionGraph;
import org.clinicsync.common.util.UuidV7Generator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Central copy of the append-only versioned FHIR store for resources written directly on central
 * (as opposed to changes arriving through the sync push endpoint).
 *
 * <p>Local central writes are stamped with the central HLC, receive node-qualified version ids
 * ({@code <ordinal>-central}) and are recorded in {@code applied_changes} under the {@code central}
 * origin with a dense sequence from {@code central_origin_seq}, so they flow through the exact same
 * {@code global_seq} timeline that edges pull.</p>
 */
@Repository
public class CentralResourceRepository {

    private static final String CENTRAL_NODE_ID = "central";

    private final JdbcClient jdbcClient;
    private final HybridLogicalClock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record HeadInfo(String headVersionIds, boolean hasConflict, boolean isTombstone) {}

    public CentralResourceRepository(JdbcClient jdbcClient, HybridLogicalClock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
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

        String versionId = VersionGraph.nextOrdinal(null) + "-" + CENTRAL_NODE_ID;
        String hlc = clock.now().toString();
        Timestamp now = Timestamp.from(Instant.now());
        long originSeq = nextCentralOriginSeq();

        insertResourceVersion(resourceType, resourceId, versionId, finalJson, null,
                CENTRAL_NODE_ID, originSeq, hlc, author, false, now);
        upsertResourceHead(resourceType, resourceId, versionId, false, now);
        recordCentralChange(resourceType, resourceId, versionId, originSeq);

        return new ResourceVersionRecord(
                resourceType, resourceId, versionId, finalJson, null,
                CENTRAL_NODE_ID, originSeq, hlc, author, false, now.toInstant()
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
            if (!VersionGraph.ids(head.headVersionIds()).contains(expected)) {
                throw new OptimisticLockException(
                        "Precondition Failed: Current version is " + head.headVersionIds() + ", but If-Match was " + ifMatchVersion
                );
            }
        }

        String versionId = VersionGraph.nextOrdinal(head.headVersionIds()) + "-" + CENTRAL_NODE_ID;
        String hlc = clock.now().toString();
        Timestamp now = Timestamp.from(Instant.now());
        String finalJson = ensureIdInJson(jsonContent, resourceId);
        long originSeq = nextCentralOriginSeq();

        insertResourceVersion(resourceType, resourceId, versionId, finalJson, head.headVersionIds(),
                CENTRAL_NODE_ID, originSeq, hlc, author, false, now);

        VersionGraph.HeadUpdate update = VersionGraph.apply(head.headVersionIds(), head.headVersionIds(), versionId);
        upsertResourceHead(resourceType, resourceId, update.headVersionIds(), update.hasConflict(), now);
        recordCentralChange(resourceType, resourceId, versionId, originSeq);

        return new ResourceVersionRecord(
                resourceType, resourceId, versionId, finalJson, head.headVersionIds(),
                CENTRAL_NODE_ID, originSeq, hlc, author, false, now.toInstant()
        );
    }

    public ResourceVersionRecord readResource(String resourceType, String resourceId) {
        HeadInfo head = getHeadInfo(resourceType, resourceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Resource " + resourceType + "/" + resourceId + " not found"));

        Set<String> heads = VersionGraph.ids(head.headVersionIds());
        List<ResourceVersionRecord> headVersions = heads.stream()
                .map(versionId -> fetchVersion(resourceType, resourceId, versionId))
                .flatMap(Optional::stream)
                .toList();

        if (headVersions.isEmpty()) {
            throw new ResourceNotFoundException("Resource " + resourceType + "/" + resourceId + " not found");
        }

        // Concurrent heads still read as exactly one resource: newest by HLC wins for display only.
        ResourceVersionRecord displayHead = headVersions.stream().max(hlcOrder()).orElseThrow();

        if (displayHead.isTombstone()) {
            throw new ResourceGoneException("Resource " + resourceType + "/" + resourceId + " has been deleted");
        }
        return displayHead;
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
                .query(CentralResourceRepository::mapVersionRecord)
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
            if (!VersionGraph.ids(head.headVersionIds()).contains(expected)) {
                throw new OptimisticLockException(
                        "Precondition Failed: Current version is " + head.headVersionIds() + ", but If-Match was " + ifMatchVersion
                );
            }
        }

        String versionId = VersionGraph.nextOrdinal(head.headVersionIds()) + "-" + CENTRAL_NODE_ID;
        String hlc = clock.now().toString();
        Timestamp now = Timestamp.from(Instant.now());
        String tombstoneJson = "{\"resourceType\":\"" + resourceType + "\",\"id\":\"" + resourceId
                + "\",\"meta\":{\"isTombstone\":true}}";
        long originSeq = nextCentralOriginSeq();

        insertResourceVersion(resourceType, resourceId, versionId, tombstoneJson, head.headVersionIds(),
                CENTRAL_NODE_ID, originSeq, hlc, author, true, now);

        VersionGraph.HeadUpdate update = VersionGraph.apply(head.headVersionIds(), head.headVersionIds(), versionId);
        upsertResourceHead(resourceType, resourceId, update.headVersionIds(), update.hasConflict(), now);
        recordCentralChange(resourceType, resourceId, versionId, originSeq);

        return new ResourceVersionRecord(
                resourceType, resourceId, versionId, tombstoneJson, head.headVersionIds(),
                CENTRAL_NODE_ID, originSeq, hlc, author, true, now.toInstant()
        );
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private long nextCentralOriginSeq() {
        return jdbcClient.sql("SELECT nextval('central_origin_seq')")
                .query(Long.class)
                .single();
    }

    /** Publishes a central-local write into the applied_changes timeline that edges pull. */
    private void recordCentralChange(String type, String id, String versionId, long originSeq) {
        jdbcClient.sql("""
                INSERT INTO applied_changes (origin_node_id, origin_seq, resource_type, resource_id, version_id, applied_at)
                VALUES (:nodeId, :seq, :type, :id, :versionId, :appliedAt)
                """)
                .param("nodeId", CENTRAL_NODE_ID)
                .param("seq", originSeq)
                .param("type", type)
                .param("id", id)
                .param("versionId", versionId)
                .param("appliedAt", Timestamp.from(Instant.now()))
                .update();
    }

    private Optional<ResourceVersionRecord> fetchVersion(String resourceType, String resourceId, String versionId) {
        return jdbcClient.sql("""
                SELECT resource_type, resource_id, version_id, content_json,
                       parent_version_ids, origin_node_id, origin_seq,
                       hlc, author, is_tombstone, created_at
                FROM resource_version
                WHERE resource_type = :type AND resource_id = :id AND version_id = :versionId
                """)
                .param("type", resourceType)
                .param("id", resourceId)
                .param("versionId", versionId)
                .query(CentralResourceRepository::mapVersionRecord)
                .optional();
    }

    private static ResourceVersionRecord mapVersionRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ResourceVersionRecord(
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
        );
    }

    private static Comparator<ResourceVersionRecord> hlcOrder() {
        return Comparator
                .comparing((ResourceVersionRecord record) -> {
                    try {
                        return Hlc.parse(record.hlc());
                    } catch (RuntimeException e) {
                        return Hlc.initial("");
                    }
                })
                .thenComparing(ResourceVersionRecord::versionId);
    }

    private Optional<HeadInfo> getHeadInfo(String resourceType, String resourceId) {
        return jdbcClient.sql("""
                SELECT rh.head_version_ids, rh.has_conflict
                FROM resource_head rh
                WHERE rh.resource_type = :type AND rh.resource_id = :id
                """)
                .param("type", resourceType)
                .param("id", resourceId)
                .query((rs, rowNum) -> new HeadInfo(
                        rs.getString("head_version_ids"),
                        rs.getBoolean("has_conflict"),
                        false
                ))
                .optional()
                .map(head -> withTombstoneState(resourceType, resourceId, head));
    }

    private HeadInfo withTombstoneState(String resourceType, String resourceId, HeadInfo head) {
        boolean anyTombstone = false;
        for (String versionId : VersionGraph.ids(head.headVersionIds())) {
            Optional<Boolean> tombstone = jdbcClient.sql("""
                    SELECT is_tombstone FROM resource_version
                    WHERE resource_type = :type AND resource_id = :id AND version_id = :versionId
                    """)
                    .param("type", resourceType)
                    .param("id", resourceId)
                    .param("versionId", versionId)
                    .query(Boolean.class)
                    .optional();
            anyTombstone |= Boolean.TRUE.equals(tombstone.orElse(false));
        }
        return new HeadInfo(head.headVersionIds(), head.hasConflict(), anyTombstone);
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

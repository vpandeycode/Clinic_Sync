package org.clinicsync.edge.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.clinicsync.common.exception.ClockDriftException;
import org.clinicsync.common.exception.OptimisticLockException;
import org.clinicsync.common.exception.ResourceGoneException;
import org.clinicsync.common.exception.ResourceNotFoundException;
import org.clinicsync.common.exception.SyncRejectedException;
import org.clinicsync.common.fhir.FhirUtil;
import org.clinicsync.common.hlc.Hlc;
import org.clinicsync.common.hlc.HybridLogicalClock;
import org.clinicsync.common.model.ChangeRecord;
import org.clinicsync.common.model.PulledChange;
import org.clinicsync.common.model.ResourceVersionRecord;
import org.clinicsync.common.sync.VersionGraph;
import org.clinicsync.common.util.UuidV7Generator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Append-only, versioned FHIR store on the edge node.
 *
 * <p>Local writes append a new {@code resource_version} row (never an in-place update), advance
 * {@code resource_head} through {@link VersionGraph} and journal the change in {@code change_log}
 * so the sync agent can push it later. Changes pulled from central are applied idempotently into
 * the same store but are <em>not</em> journalled — an edge only ever pushes its own writes.</p>
 */
@Repository
public class EdgeResourceRepository {

    private static final Logger log = LoggerFactory.getLogger(EdgeResourceRepository.class);

    private final JdbcClient jdbcClient;
    private final HybridLogicalClock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record HeadInfo(String headVersionIds, boolean hasConflict, boolean isTombstone) {}

    public EdgeResourceRepository(JdbcClient jdbcClient, HybridLogicalClock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Local writes
    // ------------------------------------------------------------------

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

        String versionId = VersionGraph.nextOrdinal(null) + "-" + originNodeId;
        String hlc = clock.now().toString();
        Timestamp now = Timestamp.from(Instant.now());

        long originSeq = recordChangeLog(resourceType, resourceId, versionId, now);
        insertResourceVersion(resourceType, resourceId, versionId, finalJson, null,
                originNodeId, originSeq, hlc, author, false, now);
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
            if (!VersionGraph.ids(head.headVersionIds()).contains(expected)) {
                throw new OptimisticLockException(
                        "Precondition Failed: Current version is " + head.headVersionIds() + ", but If-Match was " + ifMatchVersion
                );
            }
        }

        String versionId = VersionGraph.nextOrdinal(head.headVersionIds()) + "-" + originNodeId;
        String hlc = clock.now().toString();
        Timestamp now = Timestamp.from(Instant.now());
        String finalJson = ensureIdInJson(jsonContent, resourceId);

        long originSeq = recordChangeLog(resourceType, resourceId, versionId, now);
        insertResourceVersion(resourceType, resourceId, versionId, finalJson, head.headVersionIds(),
                originNodeId, originSeq, hlc, author, false, now);

        // A local edit is based on every current head, so it fast-forwards to a single head.
        VersionGraph.HeadUpdate update = VersionGraph.apply(head.headVersionIds(), head.headVersionIds(), versionId);
        upsertResourceHead(resourceType, resourceId, update.headVersionIds(), update.hasConflict(), now);

        return new ResourceVersionRecord(
                resourceType, resourceId, versionId, finalJson, head.headVersionIds(),
                originNodeId, originSeq, hlc, author, false, now.toInstant()
        );
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
            if (!VersionGraph.ids(head.headVersionIds()).contains(expected)) {
                throw new OptimisticLockException(
                        "Precondition Failed: Current version is " + head.headVersionIds() + ", but If-Match was " + ifMatchVersion
                );
            }
        }

        String versionId = VersionGraph.nextOrdinal(head.headVersionIds()) + "-" + originNodeId;
        String hlc = clock.now().toString();
        Timestamp now = Timestamp.from(Instant.now());
        String tombstoneJson = "{\"resourceType\":\"" + resourceType + "\",\"id\":\"" + resourceId
                + "\",\"meta\":{\"isTombstone\":true}}";

        long originSeq = recordChangeLog(resourceType, resourceId, versionId, now);
        insertResourceVersion(resourceType, resourceId, versionId, tombstoneJson, head.headVersionIds(),
                originNodeId, originSeq, hlc, author, true, now);

        VersionGraph.HeadUpdate update = VersionGraph.apply(head.headVersionIds(), head.headVersionIds(), versionId);
        upsertResourceHead(resourceType, resourceId, update.headVersionIds(), update.hasConflict(), now);

        return new ResourceVersionRecord(
                resourceType, resourceId, versionId, tombstoneJson, head.headVersionIds(),
                originNodeId, originSeq, hlc, author, true, now.toInstant()
        );
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    public ResourceVersionRecord readResource(String resourceType, String resourceId) {
        HeadInfo head = getHeadInfo(resourceType, resourceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Resource " + resourceType + "/" + resourceId + " not found"));

        ResourceVersionRecord displayHead = resolveDisplayHead(resourceType, resourceId, head.headVersionIds());

        if (displayHead.isTombstone()) {
            throw new ResourceGoneException("Resource " + resourceType + "/" + resourceId + " has been deleted");
        }
        return displayHead;
    }

    /**
     * When a resource has concurrent heads (offline branch), reads must still return exactly one
     * resource. The newest head by HLC wins for <em>display</em> only — every branch stays stored,
     * readable through history and flagged through {@code resource_head.has_conflict}.
     */
    private ResourceVersionRecord resolveDisplayHead(String resourceType, String resourceId, String headVersionIds) {
        Set<String> heads = VersionGraph.ids(headVersionIds);
        if (heads.isEmpty()) {
            throw new ResourceNotFoundException("Resource " + resourceType + "/" + resourceId + " not found");
        }

        List<ResourceVersionRecord> headVersions = heads.stream()
                .map(versionId -> fetchVersion(resourceType, resourceId, versionId))
                .flatMap(Optional::stream)
                .toList();

        if (headVersions.isEmpty()) {
            throw new ResourceNotFoundException("Resource " + resourceType + "/" + resourceId + " not found");
        }

        return headVersions.stream().max(hlcOrder()).orElseThrow();
    }

    private static Comparator<ResourceVersionRecord> hlcOrder() {
        return Comparator
                .comparing((ResourceVersionRecord record) -> parseHlc(record.hlc()))
                .thenComparing(ResourceVersionRecord::versionId);
    }

    private static Hlc parseHlc(String hlc) {
        try {
            return Hlc.parse(hlc);
        } catch (RuntimeException e) {
            return Hlc.initial("");
        }
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
                .query(EdgeResourceRepository::mapVersionRecord)
                .optional();
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
                .query(EdgeResourceRepository::mapVersionRecord)
                .list();

        if (list.isEmpty()) {
            throw new ResourceNotFoundException("Resource " + resourceType + "/" + resourceId + " not found");
        }

        return list;
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

    // ------------------------------------------------------------------
    // Applying changes pulled from central (idempotent)
    // ------------------------------------------------------------------

    /**
     * Applies one page of pulled changes and advances the pull cursor in the same transaction, so
     * a crash mid-page can always be replayed: re-applying an already stored version is a no-op.
     */
    @Transactional
    public void applyPullPage(List<PulledChange> changes, long nextGlobalSeq) {
        if (changes != null) {
            for (PulledChange change : changes) {
                applyRemoteChange(change);
            }
        }
        advancePulledCursor(nextGlobalSeq);
    }

    private void applyRemoteChange(PulledChange change) {
        String type = change.resourceType();
        String id = change.resourceId();
        String versionId = change.versionId();

        // Keep causal ordering: our local clock must never lag behind what we accept.
        try {
            clock.update(Hlc.parse(change.hlc()));
        } catch (ClockDriftException | IllegalArgumentException e) {
            log.warn("Could not merge remote HLC {} into local clock: {}", change.hlc(), e.getMessage());
        }

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
                        "Stored version " + type + "/" + id + "/" + versionId + " differs from the pulled payload");
            }
            return; // idempotent replay: already applied
        }

        Timestamp now = Timestamp.from(Instant.now());
        insertResourceVersion(type, id, versionId, change.contentJson(), change.parentVersionIds(),
                change.originNodeId(), change.originSeq(), change.hlc(), change.author(), change.isTombstone(), now);

        Optional<String> currentHead = findHead(type, id);
        if (currentHead.isEmpty()) {
            upsertResourceHead(type, id, versionId, false, now);
        } else {
            VersionGraph.HeadUpdate update = VersionGraph.apply(currentHead.get(), change.parentVersionIds(), versionId);
            upsertResourceHead(type, id, update.headVersionIds(), update.hasConflict(), now);
            if (update.hasConflict()) {
                log.warn("Concurrent branch for local {}/{}: heads now [{}]", type, id, update.headVersionIds());
            }
        }
    }

    // ------------------------------------------------------------------
    // Change journal and sync cursors
    // ------------------------------------------------------------------

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
                LIMIT :limit
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

    public long getLastPulledGlobalSeq(String peer) {
        return jdbcClient.sql("""
                SELECT last_pulled_global_seq FROM sync_state WHERE peer = :peer
                """)
                .param("peer", peer)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    @Transactional
    public void updateLastPushedSeq(String peer, long seq) {
        upsertSyncState(peer, seq, null);
    }

    /** Cursor only ever moves forward, even if a replayed page carries an older cursor. */
    @Transactional
    public void advancePulledCursor(long globalSeq) {
        long current = getLastPulledGlobalSeq("central");
        if (globalSeq > current) {
            upsertSyncState("central", null, globalSeq);
        }
    }

    private void upsertSyncState(String peer, Long pushedSeq, Long pulledSeq) {
        Timestamp now = Timestamp.from(Instant.now());

        StringBuilder sql = new StringBuilder("UPDATE sync_state SET ");
        boolean first = true;
        if (pushedSeq != null) {
            sql.append("last_pushed_seq = :pushed");
            first = false;
        }
        if (pulledSeq != null) {
            if (!first) {
                sql.append(", ");
            }
            sql.append("last_pulled_global_seq = :pulled");
        }
        sql.append(", last_sync_success = :successTime WHERE peer = :peer");

        var spec = jdbcClient.sql(sql.toString())
                .param("successTime", now)
                .param("peer", peer);
        if (pushedSeq != null) {
            spec = spec.param("pushed", pushedSeq);
        }
        if (pulledSeq != null) {
            spec = spec.param("pulled", pulledSeq);
        }

        int updated = spec.update();

        if (updated == 0) {
            jdbcClient.sql("""
                    INSERT INTO sync_state (peer, last_pushed_seq, last_pulled_global_seq, last_sync_success)
                    VALUES (:peer, :pushed, :pulled, :successTime)
                    """)
                    .param("peer", peer)
                    .param("pushed", pushedSeq != null ? pushedSeq : 0L)
                    .param("pulled", pulledSeq != null ? pulledSeq : 0L)
                    .param("successTime", now)
                    .update();
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

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
        Set<String> heads = VersionGraph.ids(head.headVersionIds());
        boolean anyTombstone = false;
        for (String versionId : heads) {
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

package org.clinicsync.central.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Registry of edge nodes allowed to sync: identity, Ed25519 public key, clinic scope and status.
 */
@Repository
public class NodeRegistryRepository {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String STATUS_REVOKED = "REVOKED";

    public record NodeRecord(String nodeId, String publicKey, String clinicScope, String status, Timestamp createdAt) {}

    private final JdbcClient jdbcClient;

    public NodeRegistryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<NodeRecord> findByNodeId(String nodeId) {
        return jdbcClient.sql("""
                SELECT node_id, public_key, clinic_scope, status, created_at
                FROM node_registry
                WHERE node_id = :nodeId
                """)
                .param("nodeId", nodeId)
                .query((rs, rowNum) -> new NodeRecord(
                        rs.getString("node_id"),
                        rs.getString("public_key"),
                        rs.getString("clinic_scope"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at")
                ))
                .optional();
    }

    public List<NodeRecord> findAll() {
        return jdbcClient.sql("""
                SELECT node_id, public_key, clinic_scope, status, created_at
                FROM node_registry
                ORDER BY node_id
                """)
                .query((rs, rowNum) -> new NodeRecord(
                        rs.getString("node_id"),
                        rs.getString("public_key"),
                        rs.getString("clinic_scope"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at")
                ))
                .list();
    }

    public void insert(String nodeId, String publicKey, String clinicScope, String status) {
        jdbcClient.sql("""
                INSERT INTO node_registry (node_id, public_key, clinic_scope, status, created_at)
                VALUES (:nodeId, :publicKey, :scope, :status, :createdAt)
                """)
                .param("nodeId", nodeId)
                .param("publicKey", publicKey)
                .param("scope", clinicScope)
                .param("status", status)
                .param("createdAt", Timestamp.from(Instant.now()))
                .update();
    }

    public void updateCredentials(String nodeId, String publicKey, String clinicScope) {
        jdbcClient.sql("""
                UPDATE node_registry
                SET public_key = :publicKey, clinic_scope = :scope
                WHERE node_id = :nodeId
                """)
                .param("nodeId", nodeId)
                .param("publicKey", publicKey)
                .param("scope", clinicScope)
                .update();
    }

    public void updateStatus(String nodeId, String status) {
        jdbcClient.sql("""
                UPDATE node_registry
                SET status = :status
                WHERE node_id = :nodeId
                """)
                .param("nodeId", nodeId)
                .param("status", status)
                .update();
    }

    /** node_id -> set of clinic scope tags, used to filter pull payloads per requester. */
    public Map<String, Set<String>> scopeIndex() {
        List<String[]> rows = jdbcClient.sql("SELECT node_id, clinic_scope FROM node_registry")
                .query((rs, rowNum) -> new String[]{rs.getString("node_id"), rs.getString("clinic_scope")})
                .list();

        Map<String, Set<String>> index = new HashMap<>();
        for (String[] row : rows) {
            index.put(row[0], ScopeTags.parse(row[1]));
        }
        return index;
    }

    /** Normalizes a comma-separated clinic scope declaration into a tag set. */
    public static final class ScopeTags {
        private ScopeTags() {}

        public static Set<String> parse(String scope) {
            Set<String> tags = new java.util.LinkedHashSet<>();
            if (scope == null || scope.isBlank()) {
                return tags;
            }
            for (String part : scope.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    tags.add(trimmed);
                }
            }
            return tags;
        }
    }
}

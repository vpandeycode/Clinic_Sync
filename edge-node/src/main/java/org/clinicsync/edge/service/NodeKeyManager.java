package org.clinicsync.edge.service;

import org.clinicsync.common.crypto.Ed25519Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Optional;

/**
 * Holds this edge node's Ed25519 identity.
 *
 * <p>The keypair is generated once and persisted in the local (Phase 5: AES-encrypted) database so
 * the node keeps the same identity across restarts — the central {@code node_registry} knows the
 * public key and sync payloads are attributable to this device. Losing the database means losing
 * the identity and re-enrolment; see THREAT_MODEL.md for the lost-device limitation.</p>
 */
@Service
public class NodeKeyManager {

    private static final Logger log = LoggerFactory.getLogger(NodeKeyManager.class);

    private final JdbcClient jdbcClient;
    private final String nodeId;

    private volatile KeyPair cached;

    public NodeKeyManager(JdbcClient jdbcClient, @Value("${clinic.node-id}") String nodeId) {
        this.jdbcClient = jdbcClient;
        this.nodeId = nodeId;
    }

    public synchronized PrivateKey privateKey() {
        return load().getPrivate();
    }

    public synchronized String publicKeyBase64() {
        return Ed25519Util.encodePublicKey(load().getPublic());
    }

    private KeyPair load() {
        if (cached != null) {
            return cached;
        }

        Optional<KeyPair> stored = jdbcClient.sql("""
                SELECT private_key, public_key FROM node_keypair WHERE node_id = :nodeId
                """)
                .param("nodeId", nodeId)
                .query((rs, rowNum) -> {
                    PublicKey publicKey = Ed25519Util.decodePublicKey(rs.getString("public_key"));
                    PrivateKey privateKey = Ed25519Util.decodePrivateKey(rs.getString("private_key"));
                    return new KeyPair(publicKey, privateKey);
                })
                .optional();

        if (stored.isPresent()) {
            cached = stored.get();
            return cached;
        }

        KeyPair generated = Ed25519Util.generateKeyPair();
        try {
            jdbcClient.sql("""
                    INSERT INTO node_keypair (node_id, private_key, public_key, created_at)
                    VALUES (:nodeId, :privateKey, :publicKey, CURRENT_TIMESTAMP)
                    """)
                    .param("nodeId", nodeId)
                    .param("privateKey", Ed25519Util.encodePrivateKey(generated.getPrivate()))
                    .param("publicKey", Ed25519Util.encodePublicKey(generated.getPublic()))
                    .update();
        } catch (RuntimeException e) {
            // Another instance won the race: reload the stored pair.
            return load();
        }

        log.info("Generated new Ed25519 identity for edge node {}", nodeId);
        cached = generated;
        return cached;
    }
}

package org.clinicsync.common.model;

import java.io.Serializable;

/**
 * Registration payload an edge node posts to central so its Ed25519 public key and clinic scope
 * are on record before it may push or pull.
 */
public record NodeRegistration(
        String nodeId,
        String publicKey,
        String clinicScope
) implements Serializable {
    public NodeRegistration {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        if (publicKey == null || publicKey.isBlank()) {
            throw new IllegalArgumentException("publicKey must not be blank");
        }
        if (clinicScope == null || clinicScope.isBlank()) {
            throw new IllegalArgumentException("clinicScope must not be blank");
        }
    }
}

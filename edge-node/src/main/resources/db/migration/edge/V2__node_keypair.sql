-- Edge Node Schema Migration V2: local Ed25519 identity for this device.
-- Stored in the (Phase 5: AES-encrypted) local database so the node keeps a stable identity
-- across restarts and its public key can be registered at the central server.
CREATE TABLE IF NOT EXISTS node_keypair (
    node_id VARCHAR(64) PRIMARY KEY,
    private_key TEXT NOT NULL,
    public_key TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

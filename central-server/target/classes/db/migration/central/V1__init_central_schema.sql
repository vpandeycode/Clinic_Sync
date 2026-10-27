-- Central Server Schema Migration V1

CREATE TABLE IF NOT EXISTS resource_version (
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    content_json TEXT NOT NULL,
    parent_version_ids VARCHAR(255),
    origin_node_id VARCHAR(64) NOT NULL,
    origin_seq BIGINT NOT NULL,
    hlc VARCHAR(128) NOT NULL,
    author VARCHAR(128),
    is_tombstone BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (resource_type, resource_id, version_id)
);

CREATE TABLE IF NOT EXISTS resource_head (
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    head_version_ids VARCHAR(255) NOT NULL,
    has_conflict BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (resource_type, resource_id)
);

CREATE TABLE IF NOT EXISTS applied_changes (
    global_seq BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    origin_node_id VARCHAR(64) NOT NULL,
    origin_seq BIGINT NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    applied_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_origin_change UNIQUE (origin_node_id, origin_seq)
);

CREATE TABLE IF NOT EXISTS node_registry (
    node_id VARCHAR(64) PRIMARY KEY,
    public_key TEXT,
    clinic_scope VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS conflict_case (
    case_id VARCHAR(64) PRIMARY KEY,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    conflicting_version_ids TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    resolution_version_id VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS patient_link (
    link_id VARCHAR(64) PRIMARY KEY,
    canonical_patient_id VARCHAR(64) NOT NULL,
    linked_patient_id VARCHAR(64) NOT NULL,
    link_type VARCHAR(32) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS audit_event (
    event_id VARCHAR(64) PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64),
    resource_id VARCHAR(64),
    principal VARCHAR(128),
    outcome VARCHAR(32) NOT NULL,
    event_timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    details TEXT
);

CREATE INDEX IF NOT EXISTS idx_applied_global_seq ON applied_changes(global_seq);
CREATE INDEX IF NOT EXISTS idx_applied_resource ON applied_changes(resource_type, resource_id);
CREATE INDEX IF NOT EXISTS idx_central_version_lookup ON resource_version(resource_type, resource_id);

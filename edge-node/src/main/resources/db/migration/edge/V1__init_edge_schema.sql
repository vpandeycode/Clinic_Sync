-- Edge Node Schema Migration V1

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

CREATE TABLE IF NOT EXISTS change_log (
    seq BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    version_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sync_state (
    peer VARCHAR(64) PRIMARY KEY,
    last_pushed_seq BIGINT NOT NULL DEFAULT 0,
    last_pulled_global_seq BIGINT NOT NULL DEFAULT 0,
    last_sync_attempt TIMESTAMP WITH TIME ZONE,
    last_sync_success TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_change_log_seq ON change_log(seq);
CREATE INDEX IF NOT EXISTS idx_res_version_lookup ON resource_version(resource_type, resource_id);

CREATE TABLE local_preview (
    id VARCHAR(36) PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    state VARCHAR(32) NOT NULL,
    expires_at BIGINT NOT NULL,
    record_json LONGTEXT NOT NULL
);

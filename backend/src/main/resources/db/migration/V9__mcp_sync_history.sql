CREATE TABLE mcp_sync_event (
    id VARCHAR(36) PRIMARY KEY,
    server_id VARCHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    protocol_version VARCHAR(32),
    tool_count INT NOT NULL DEFAULT 0,
    resource_count INT NOT NULL DEFAULT 0,
    prompt_count INT NOT NULL DEFAULT 0,
    added_count INT NOT NULL DEFAULT 0,
    removed_count INT NOT NULL DEFAULT 0,
    unchanged_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(1000),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_mcp_sync_event_server FOREIGN KEY (server_id) REFERENCES mcp_server(id)
);

CREATE INDEX idx_mcp_sync_event_server_created ON mcp_sync_event(server_id, created_at);

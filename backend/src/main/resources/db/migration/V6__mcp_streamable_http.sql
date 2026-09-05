CREATE TABLE mcp_server (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    endpoint_url VARCHAR(1000) NOT NULL,
    api_key_env VARCHAR(160),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(40) NOT NULL,
    protocol_version VARCHAR(40),
    remote_server_name VARCHAR(160),
    remote_server_version VARCHAR(80),
    last_error VARCHAR(1000),
    last_synced_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_mcp_server_name UNIQUE (name)
);

CREATE TABLE mcp_tool_catalog (
    public_name VARCHAR(120) PRIMARY KEY,
    server_id VARCHAR(36) NOT NULL,
    remote_name VARCHAR(240) NOT NULL,
    display_name VARCHAR(240) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    input_schema TEXT NOT NULL,
    capability VARCHAR(40) NOT NULL,
    risk_level VARCHAR(40) NOT NULL,
    timeout_seconds INT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    schema_sha256 VARCHAR(64) NOT NULL,
    discovered_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_mcp_tool_revision UNIQUE (server_id, remote_name, schema_sha256),
    CONSTRAINT fk_mcp_tool_server FOREIGN KEY (server_id) REFERENCES mcp_server(id)
);

CREATE INDEX idx_mcp_tool_server_active ON mcp_tool_catalog(server_id, active);

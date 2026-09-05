CREATE TABLE mcp_resource_catalog (
    public_id VARCHAR(120) PRIMARY KEY,
    server_id VARCHAR(36) NOT NULL,
    uri_value TEXT NOT NULL,
    uri_sha256 VARCHAR(64) NOT NULL,
    resource_name VARCHAR(240) NOT NULL,
    display_name VARCHAR(240) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    mime_type VARCHAR(240),
    resource_size BIGINT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    discovered_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_mcp_resource_uri UNIQUE (server_id, uri_sha256),
    CONSTRAINT fk_mcp_resource_server FOREIGN KEY (server_id) REFERENCES mcp_server(id)
);

CREATE TABLE mcp_prompt_catalog (
    public_name VARCHAR(120) PRIMARY KEY,
    server_id VARCHAR(36) NOT NULL,
    remote_name VARCHAR(240) NOT NULL,
    display_name VARCHAR(240) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    arguments_json TEXT NOT NULL,
    fingerprint_sha256 VARCHAR(64) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    discovered_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_mcp_prompt_revision UNIQUE (server_id, remote_name, fingerprint_sha256),
    CONSTRAINT fk_mcp_prompt_server FOREIGN KEY (server_id) REFERENCES mcp_server(id)
);

CREATE INDEX idx_mcp_resource_active ON mcp_resource_catalog(server_id, active);
CREATE INDEX idx_mcp_prompt_active ON mcp_prompt_catalog(server_id, active);

ALTER TABLE mcp_server ADD COLUMN transport VARCHAR(40) NOT NULL DEFAULT 'STREAMABLE_HTTP';
ALTER TABLE mcp_server MODIFY COLUMN endpoint_url VARCHAR(1000) NULL;
ALTER TABLE mcp_server ADD COLUMN command_path VARCHAR(1000) NULL;
ALTER TABLE mcp_server ADD COLUMN arguments_json TEXT NULL;
ALTER TABLE mcp_server ADD COLUMN working_directory VARCHAR(1000) NULL;
ALTER TABLE mcp_server ADD COLUMN environment_json TEXT NULL;

ALTER TABLE mcp_tool_catalog ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
CREATE INDEX idx_mcp_tool_available ON mcp_tool_catalog(server_id, active, enabled);

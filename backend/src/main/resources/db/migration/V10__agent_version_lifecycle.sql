ALTER TABLE agent_version ADD COLUMN archived_at TIMESTAMP(6) NULL;
CREATE INDEX idx_agent_version_active ON agent_version(agent_definition_id, archived_at, version_number);

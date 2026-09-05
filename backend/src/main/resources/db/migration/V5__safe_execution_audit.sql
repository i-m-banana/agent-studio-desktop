ALTER TABLE approval_request ADD COLUMN conversation_id VARCHAR(36);
ALTER TABLE approval_request ADD COLUMN agent_version_id VARCHAR(36);
ALTER TABLE approval_request ADD COLUMN capability VARCHAR(40);
ALTER TABLE approval_request ADD COLUMN risk_level VARCHAR(40);
ALTER TABLE approval_request ADD COLUMN target_environment VARCHAR(160);
ALTER TABLE approval_request ADD CONSTRAINT fk_approval_conversation
    FOREIGN KEY (conversation_id) REFERENCES conversation(id);
ALTER TABLE approval_request ADD CONSTRAINT fk_approval_version
    FOREIGN KEY (agent_version_id) REFERENCES agent_version(id);

CREATE TABLE audit_event (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    agent_version_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    tool_name VARCHAR(120),
    capability VARCHAR(40),
    risk_level VARCHAR(40),
    status VARCHAR(40) NOT NULL,
    arguments_sha256 VARCHAR(64),
    details VARCHAR(1000),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_audit_run FOREIGN KEY (run_id) REFERENCES agent_run(id),
    CONSTRAINT fk_audit_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id),
    CONSTRAINT fk_audit_version FOREIGN KEY (agent_version_id) REFERENCES agent_version(id)
);

CREATE INDEX idx_audit_run_time ON audit_event(run_id, created_at);
CREATE INDEX idx_audit_conversation_time ON audit_event(conversation_id, created_at);

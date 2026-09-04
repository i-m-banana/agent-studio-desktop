CREATE TABLE agent_tool_binding (
    agent_definition_id VARCHAR(36) NOT NULL,
    tool_name VARCHAR(120) NOT NULL,
    PRIMARY KEY (agent_definition_id, tool_name),
    CONSTRAINT fk_agent_tool_definition FOREIGN KEY (agent_definition_id) REFERENCES agent_definition(id)
);

CREATE TABLE agent_version_tool (
    agent_version_id VARCHAR(36) NOT NULL,
    tool_name VARCHAR(120) NOT NULL,
    PRIMARY KEY (agent_version_id, tool_name),
    CONSTRAINT fk_version_tool_version FOREIGN KEY (agent_version_id) REFERENCES agent_version(id)
);

CREATE TABLE agent_run (
    id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    agent_version_id VARCHAR(36) NOT NULL,
    status VARCHAR(40) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    error_message VARCHAR(1000),
    CONSTRAINT fk_run_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id),
    CONSTRAINT fk_run_version FOREIGN KEY (agent_version_id) REFERENCES agent_version(id)
);

CREATE TABLE run_step (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    step_number INT NOT NULL,
    step_type VARCHAR(40) NOT NULL,
    status VARCHAR(40) NOT NULL,
    tool_call_id VARCHAR(160),
    tool_name VARCHAR(120),
    input_json TEXT,
    output_text TEXT,
    duration_ms BIGINT,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_run_step_number UNIQUE (run_id, step_number),
    CONSTRAINT fk_step_run FOREIGN KEY (run_id) REFERENCES agent_run(id)
);

CREATE INDEX idx_run_conversation_time ON agent_run(conversation_id, started_at);
CREATE INDEX idx_step_run_number ON run_step(run_id, step_number);

CREATE TABLE release_task (
    id VARCHAR(36) PRIMARY KEY,
    source_step_id VARCHAR(36) NOT NULL UNIQUE,
    run_id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    agent_version_id VARCHAR(36) NOT NULL,
    tool_call_id VARCHAR(160) NOT NULL,
    tool_name VARCHAR(120) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_release_task_step FOREIGN KEY (source_step_id) REFERENCES run_step(id),
    CONSTRAINT fk_release_task_run FOREIGN KEY (run_id) REFERENCES agent_run(id)
);
CREATE INDEX idx_release_task_time ON release_task(created_at);

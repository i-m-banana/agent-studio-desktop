CREATE TABLE approval_request (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    tool_call_id VARCHAR(160) NOT NULL,
    tool_name VARCHAR(120) NOT NULL,
    arguments_json TEXT NOT NULL,
    arguments_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(40) NOT NULL,
    reason VARCHAR(500),
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    decided_at TIMESTAMP(6),
    CONSTRAINT uk_approval_run_call UNIQUE (run_id, tool_call_id),
    CONSTRAINT fk_approval_run FOREIGN KEY (run_id) REFERENCES agent_run(id)
);

CREATE INDEX idx_approval_run_status ON approval_request(run_id, status);

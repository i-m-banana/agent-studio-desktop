CREATE TABLE release_workflow (
    id VARCHAR(36) PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    target_key VARCHAR(64) NOT NULL,
    record_json LONGTEXT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE release_workflow_lease (
    target_key VARCHAR(64) PRIMARY KEY,
    workflow_id VARCHAR(36) NOT NULL UNIQUE
);
CREATE TABLE release_workflow_operation (
    operation_id VARCHAR(36) PRIMARY KEY,
    workflow_id VARCHAR(36) NOT NULL,
    run_id VARCHAR(36) NOT NULL UNIQUE
);
CREATE TABLE release_workflow_member (
    id VARCHAR(36) PRIMARY KEY,
    workflow_id VARCHAR(36) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    call_id VARCHAR(80) NOT NULL,
    phase VARCHAR(40) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    UNIQUE (run_id, call_id)
);

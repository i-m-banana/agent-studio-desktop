CREATE TABLE project_runtime (
    project_id VARCHAR(36) PRIMARY KEY,
    record_json LONGTEXT NOT NULL
);
CREATE TABLE mysql_verification (
    id VARCHAR(36) PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    state VARCHAR(32) NOT NULL,
    record_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);

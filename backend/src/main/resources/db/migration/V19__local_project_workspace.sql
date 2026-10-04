CREATE TABLE local_project (
    id VARCHAR(36) PRIMARY KEY,
    configuration_json LONGTEXT NOT NULL,
    revision INT NOT NULL,
    archived BOOLEAN NOT NULL DEFAULT FALSE
);
ALTER TABLE conversation ADD COLUMN local_project_id VARCHAR(36);
ALTER TABLE conversation ADD COLUMN local_project_revision INT;
ALTER TABLE conversation ADD COLUMN local_workspace_identity VARCHAR(500);

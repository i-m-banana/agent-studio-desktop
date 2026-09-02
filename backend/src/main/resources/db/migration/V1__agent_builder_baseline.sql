CREATE TABLE model_profile (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    provider VARCHAR(40) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    api_key_env VARCHAR(160) NOT NULL,
    temperature DECIMAL(4, 3) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_model_profile_name UNIQUE (name)
);

CREATE TABLE agent_definition (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL,
    draft_model_profile_id VARCHAR(36) NOT NULL,
    draft_system_prompt TEXT NOT NULL,
    latest_version_number INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_agent_definition_name UNIQUE (name),
    CONSTRAINT fk_agent_draft_model FOREIGN KEY (draft_model_profile_id) REFERENCES model_profile(id)
);

CREATE TABLE agent_version (
    id VARCHAR(36) PRIMARY KEY,
    agent_definition_id VARCHAR(36) NOT NULL,
    version_number INT NOT NULL,
    model_profile_id VARCHAR(36) NOT NULL,
    model_profile_name VARCHAR(120) NOT NULL,
    provider VARCHAR(40) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    api_key_env VARCHAR(160) NOT NULL,
    temperature DECIMAL(4, 3) NOT NULL,
    system_prompt TEXT NOT NULL,
    published_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_agent_version UNIQUE (agent_definition_id, version_number),
    CONSTRAINT fk_version_agent FOREIGN KEY (agent_definition_id) REFERENCES agent_definition(id),
    CONSTRAINT fk_version_model FOREIGN KEY (model_profile_id) REFERENCES model_profile(id)
);

CREATE TABLE conversation (
    id VARCHAR(36) PRIMARY KEY,
    agent_version_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_conversation_version FOREIGN KEY (agent_version_id) REFERENCES agent_version(id)
);

CREATE TABLE message (
    id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id)
);

CREATE INDEX idx_agent_version_definition ON agent_version(agent_definition_id);
CREATE INDEX idx_message_conversation_time ON message(conversation_id, created_at);

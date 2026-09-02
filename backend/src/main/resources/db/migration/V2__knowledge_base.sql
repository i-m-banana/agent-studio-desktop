CREATE TABLE knowledge_base (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_knowledge_base_name UNIQUE (name)
);

CREATE TABLE knowledge_document (
    id VARCHAR(36) PRIMARY KEY,
    knowledge_base_id VARCHAR(36) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(160) NOT NULL,
    file_size BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    stored_path VARCHAR(700) NOT NULL,
    status VARCHAR(30) NOT NULL,
    chunk_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(1000),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_document_knowledge_base FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base(id)
);

CREATE INDEX idx_document_knowledge_base ON knowledge_document(knowledge_base_id);

ALTER TABLE agent_definition ADD COLUMN draft_knowledge_base_id VARCHAR(36);
ALTER TABLE agent_definition ADD CONSTRAINT fk_agent_draft_knowledge
    FOREIGN KEY (draft_knowledge_base_id) REFERENCES knowledge_base(id);

ALTER TABLE agent_version ADD COLUMN knowledge_base_id VARCHAR(36);
ALTER TABLE agent_version ADD CONSTRAINT fk_version_knowledge
    FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base(id);

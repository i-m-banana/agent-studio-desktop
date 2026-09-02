package com.agentstudio.knowledge;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "agent-studio.vector", name = "enabled", havingValue = "true")
public class VectorSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public VectorSchemaInitializer(@Qualifier("vectorJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_chunk (
                    chunk_id VARCHAR(36) PRIMARY KEY,
                    knowledge_base_id VARCHAR(36) NOT NULL,
                    document_id VARCHAR(36) NOT NULL,
                    chunk_index INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    embedding vector(384) NOT NULL,
                    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunk_knowledge_base ON knowledge_chunk(knowledge_base_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunk_embedding_hnsw ON knowledge_chunk USING hnsw (embedding vector_cosine_ops)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_chunk_v2 (
                    chunk_id VARCHAR(36) PRIMARY KEY,
                    knowledge_base_id VARCHAR(36) NOT NULL,
                    document_id VARCHAR(36) NOT NULL,
                    chunk_index INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    embedding vector(1024) NOT NULL,
                    embedding_model VARCHAR(160) NOT NULL,
                    embedding_version VARCHAR(120) NOT NULL,
                    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunk_v2_scope ON knowledge_chunk_v2(knowledge_base_id, embedding_version)");
    }
}

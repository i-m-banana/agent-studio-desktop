package com.agentstudio.knowledge;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "agent-studio.vector", name = "enabled", havingValue = "true")
public class VectorChunkRepository {

    private final JdbcTemplate jdbc;
    private final LocalHashEmbedding embedding;

    public VectorChunkRepository(@Qualifier("vectorJdbcTemplate") JdbcTemplate jdbc,
                                 LocalHashEmbedding embedding) {
        this.jdbc = jdbc;
        this.embedding = embedding;
    }

    public void replaceDocument(String documentId, List<KnowledgeChunk> chunks) {
        deleteDocument(documentId);
        for (var chunk : chunks) {
            jdbc.update("""
                    INSERT INTO knowledge_chunk
                        (chunk_id, knowledge_base_id, document_id, chunk_index, content, embedding, metadata)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS vector), CAST(? AS jsonb))
                    """, chunk.id(), chunk.knowledgeBaseId(), chunk.documentId(), chunk.chunkIndex(),
                    chunk.content(), embedding.asPgVector(embedding.embed(chunk.content())),
                    "{\"fileName\":" + jsonString(chunk.fileName()) + "}");
        }
    }

    public List<RagSource> search(String knowledgeBaseId, String query, int limit) {
        var vector = embedding.asPgVector(embedding.embed(query));
        return jdbc.query("""
                SELECT document_id, chunk_index, content,
                       metadata ->> 'fileName' AS file_name,
                       1 - (embedding <=> CAST(? AS vector)) AS score
                FROM knowledge_chunk
                WHERE knowledge_base_id = ?
                ORDER BY embedding <=> CAST(? AS vector)
                LIMIT ?
                """, (rs, rowNumber) -> new RagSource(
                        rs.getString("document_id"), rs.getString("file_name"),
                        rs.getInt("chunk_index"), rs.getString("content"), rs.getDouble("score")),
                vector, knowledgeBaseId, vector, limit);
    }

    public void deleteDocument(String documentId) {
        jdbc.update("DELETE FROM knowledge_chunk WHERE document_id = ?", documentId);
    }

    private String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

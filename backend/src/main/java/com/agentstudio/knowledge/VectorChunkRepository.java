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
    private final EmbeddingGateway embedding;

    public VectorChunkRepository(@Qualifier("vectorJdbcTemplate") JdbcTemplate jdbc,
                                 EmbeddingGateway embedding) {
        this.jdbc = jdbc;
        this.embedding = embedding;
    }

    public void replaceDocument(String documentId, List<KnowledgeChunk> chunks) throws Exception {
        var vectors = embedding.embedDocuments(chunks.stream().map(KnowledgeChunk::content).toList());
        if (vectors.size() != chunks.size()) throw new IllegalStateException("Embedding 数量与 chunk 数量不匹配");
        deleteCurrentIndex(documentId);
        for (int index = 0; index < chunks.size(); index++) {
            var chunk = chunks.get(index);
            var vector = vectors.get(index);
            EmbeddingVectors.validate(vector, embedding.dimensions());
            if (embedding.dimensions() == LocalHashEmbedding.DIMENSIONS) {
                jdbc.update("""
                    INSERT INTO knowledge_chunk
                        (chunk_id, knowledge_base_id, document_id, chunk_index, content, embedding, metadata)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS vector), CAST(? AS jsonb))
                    """, chunk.id(), chunk.knowledgeBaseId(), chunk.documentId(), chunk.chunkIndex(),
                        chunk.content(), EmbeddingVectors.asPgVector(vector), metadata(chunk));
            } else if (embedding.dimensions() == 1024) {
                jdbc.update("""
                    INSERT INTO knowledge_chunk_v2
                        (chunk_id, knowledge_base_id, document_id, chunk_index, content, embedding,
                         embedding_model, embedding_version, metadata)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS vector), ?, ?, CAST(? AS jsonb))
                    """, chunk.id(), chunk.knowledgeBaseId(), chunk.documentId(), chunk.chunkIndex(),
                        chunk.content(), EmbeddingVectors.asPgVector(vector), embedding.modelName(),
                        embedding.indexVersion(), metadata(chunk));
            } else {
                throw new IllegalStateException("当前向量存储不支持 " + embedding.dimensions() + " 维 embedding");
            }
        }
    }

    public List<RagSource> search(String knowledgeBaseId, String query, int limit) throws Exception {
        var queryEmbedding = embedding.embedQuery(query);
        EmbeddingVectors.validate(queryEmbedding, embedding.dimensions());
        var vector = EmbeddingVectors.asPgVector(queryEmbedding);
        if (embedding.dimensions() == LocalHashEmbedding.DIMENSIONS) {
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
        if (embedding.dimensions() == 1024) {
            return jdbc.query("""
                SELECT document_id, chunk_index, content,
                       metadata ->> 'fileName' AS file_name,
                       1 - (embedding <=> CAST(? AS vector)) AS score
                FROM knowledge_chunk_v2
                WHERE knowledge_base_id = ? AND embedding_version = ?
                ORDER BY embedding <=> CAST(? AS vector)
                LIMIT ?
                """, (rs, rowNumber) -> new RagSource(
                    rs.getString("document_id"), rs.getString("file_name"),
                    rs.getInt("chunk_index"), rs.getString("content"), rs.getDouble("score")),
                    vector, knowledgeBaseId, embedding.indexVersion(), vector, limit);
        }
        throw new IllegalStateException("当前向量存储不支持 " + embedding.dimensions() + " 维 embedding");
    }

    public void deleteDocument(String documentId) {
        jdbc.update("DELETE FROM knowledge_chunk WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM knowledge_chunk_v2 WHERE document_id = ?", documentId);
    }

    public String embeddingDescription() {
        return embedding.modelName() + " · " + embedding.dimensions() + " 维 · " + embedding.indexVersion();
    }

    public void deleteCurrentIndex(String documentId) {
        if (embedding.dimensions() == LocalHashEmbedding.DIMENSIONS) {
            jdbc.update("DELETE FROM knowledge_chunk WHERE document_id = ?", documentId);
        } else {
            jdbc.update("DELETE FROM knowledge_chunk_v2 WHERE document_id = ? AND embedding_version = ?",
                    documentId, embedding.indexVersion());
        }
    }

    private String metadata(KnowledgeChunk chunk) {
        return "{\"fileName\":" + jsonString(chunk.fileName()) + "}";
    }

    private String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

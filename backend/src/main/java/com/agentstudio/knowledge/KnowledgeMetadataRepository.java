package com.agentstudio.knowledge;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeMetadataRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public KnowledgeMetadataRepository(
            @Qualifier("primaryNamedParameterJdbcTemplate") NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<KnowledgeBase> findBases() {
        return jdbc.query("SELECT * FROM knowledge_base ORDER BY updated_at DESC", Map.of(),
                KnowledgeMetadataRepository::mapBase);
    }

    public Optional<KnowledgeBase> findBase(String id) {
        return jdbc.query("SELECT * FROM knowledge_base WHERE id = :id", Map.of("id", id),
                KnowledgeMetadataRepository::mapBase).stream().findFirst();
    }

    public void insertBase(KnowledgeBase knowledgeBase) {
        jdbc.update("""
                INSERT INTO knowledge_base (id, name, description, created_at, updated_at)
                VALUES (:id, :name, :description, :createdAt, :updatedAt)
                """, new MapSqlParameterSource()
                .addValue("id", knowledgeBase.id()).addValue("name", knowledgeBase.name())
                .addValue("description", knowledgeBase.description())
                .addValue("createdAt", Timestamp.from(knowledgeBase.createdAt()))
                .addValue("updatedAt", Timestamp.from(knowledgeBase.updatedAt())));
    }

    public List<KnowledgeDocument> findDocuments(String knowledgeBaseId) {
        return jdbc.query("""
                SELECT * FROM knowledge_document
                WHERE knowledge_base_id = :knowledgeBaseId ORDER BY created_at DESC
                """, Map.of("knowledgeBaseId", knowledgeBaseId), KnowledgeMetadataRepository::mapDocument);
    }

    public Optional<KnowledgeDocument> findDocument(String documentId) {
        return jdbc.query("SELECT * FROM knowledge_document WHERE id = :id", Map.of("id", documentId),
                KnowledgeMetadataRepository::mapDocument).stream().findFirst();
    }

    public void insertDocument(KnowledgeDocument document) {
        jdbc.update("""
                INSERT INTO knowledge_document
                    (id, knowledge_base_id, file_name, media_type, file_size, sha256, stored_path,
                     status, chunk_count, error_message, created_at, updated_at)
                VALUES
                    (:id, :knowledgeBaseId, :fileName, :mediaType, :fileSize, :sha256, :storedPath,
                     :status, :chunkCount, :errorMessage, :createdAt, :updatedAt)
                """, documentParameters(document));
    }

    public void updateDocumentStatus(String id, String status, int chunkCount, String errorMessage) {
        jdbc.update("""
                UPDATE knowledge_document
                SET status = :status, chunk_count = :chunkCount,
                    error_message = :errorMessage, updated_at = :updatedAt
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("status", status)
                .addValue("chunkCount", chunkCount).addValue("errorMessage", errorMessage)
                .addValue("updatedAt", Timestamp.from(Instant.now())));
    }

    public void deleteDocument(String id) {
        jdbc.update("DELETE FROM knowledge_document WHERE id = :id", Map.of("id", id));
    }

    private static MapSqlParameterSource documentParameters(KnowledgeDocument document) {
        return new MapSqlParameterSource()
                .addValue("id", document.id()).addValue("knowledgeBaseId", document.knowledgeBaseId())
                .addValue("fileName", document.fileName()).addValue("mediaType", document.mediaType())
                .addValue("fileSize", document.fileSize()).addValue("sha256", document.sha256())
                .addValue("storedPath", document.storedPath()).addValue("status", document.status())
                .addValue("chunkCount", document.chunkCount()).addValue("errorMessage", document.errorMessage())
                .addValue("createdAt", Timestamp.from(document.createdAt()))
                .addValue("updatedAt", Timestamp.from(document.updatedAt()));
    }

    private static KnowledgeBase mapBase(ResultSet rs, int rowNumber) throws SQLException {
        return new KnowledgeBase(rs.getString("id"), rs.getString("name"), rs.getString("description"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static KnowledgeDocument mapDocument(ResultSet rs, int rowNumber) throws SQLException {
        return new KnowledgeDocument(
                rs.getString("id"), rs.getString("knowledge_base_id"), rs.getString("file_name"),
                rs.getString("media_type"), rs.getLong("file_size"), rs.getString("sha256"),
                rs.getString("stored_path"), rs.getString("status"), rs.getInt("chunk_count"),
                rs.getString("error_message"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}

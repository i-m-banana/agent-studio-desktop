package com.agentstudio.conversation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.agentstudio.model.ModelMessage;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ConversationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String create(String agentVersionId) {
        var id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO conversation (id, agent_version_id, created_at) VALUES (:id, :versionId, :createdAt)",
                Map.of("id", id, "versionId", agentVersionId, "createdAt", Timestamp.from(Instant.now())));
        return id;
    }

    public Optional<String> findAgentVersionId(String conversationId) {
        return jdbc.query("SELECT agent_version_id FROM conversation WHERE id = :id", Map.of("id", conversationId),
                (rs, rowNumber) -> rs.getString("agent_version_id")).stream().findFirst();
    }

    public void addMessage(String conversationId, String role, String content) {
        jdbc.update("""
                INSERT INTO message (id, conversation_id, role, content, created_at)
                VALUES (:id, :conversationId, :role, :content, :createdAt)
                """, Map.of(
                "id", UUID.randomUUID().toString(),
                "conversationId", conversationId,
                "role", role,
                "content", content,
                "createdAt", Timestamp.from(Instant.now())));
    }

    public List<ModelMessage> messages(String conversationId) {
        return jdbc.query("""
                SELECT role, content FROM message
                WHERE conversation_id = :conversationId
                ORDER BY created_at, id
                """, Map.of("conversationId", conversationId),
                (rs, rowNumber) -> new ModelMessage(rs.getString("role"), rs.getString("content")));
    }
}


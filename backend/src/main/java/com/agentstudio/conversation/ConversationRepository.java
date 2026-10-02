package com.agentstudio.conversation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.agentstudio.model.ModelMessage;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationRepository {
    public record Summary(String id, String agentVersionId, Instant createdAt, Instant updatedAt,
                          String preview, int messageCount, String activeRunId) {}
    public record HistoryMessage(String id, String role, String content, Instant createdAt) {}

    public List<Summary> list(int limit, int offset) {
        return list(limit, offset, false);
    }

    public List<Summary> list(int limit, int offset, boolean deleted) {
        return jdbc.query("""
                SELECT c.*, COALESCE((SELECT MAX(m.created_at) FROM message m WHERE m.conversation_id=c.id),c.created_at) AS updated_at,
                  (SELECT COUNT(*) FROM message m WHERE m.conversation_id=c.id) AS message_count,
                  (SELECT SUBSTRING(m.content,1,100) FROM message m WHERE m.conversation_id=c.id AND m.role='user'
                    ORDER BY m.created_at,m.id LIMIT 1) AS preview,
                  (SELECT r.id FROM agent_run r WHERE r.conversation_id=c.id
                    AND r.status NOT IN ('COMPLETED','FAILED','CANCELLED','TIMED_OUT','INTERRUPTED')
                    ORDER BY r.started_at DESC LIMIT 1) AS active_run_id
                FROM conversation c WHERE (:deleted=true AND c.deleted_at IS NOT NULL) OR (:deleted=false AND c.deleted_at IS NULL)
                ORDER BY updated_at DESC,c.id DESC LIMIT :limit OFFSET :offset
                """, Map.of("deleted", deleted, "limit", Math.max(1, Math.min(limit,100)), "offset", Math.max(0,offset)), (rs,row) ->
                new Summary(rs.getString("id"), rs.getString("agent_version_id"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(), rs.getString("preview"), rs.getInt("message_count"), rs.getString("active_run_id")));
    }

    public List<HistoryMessage> historyMessages(String conversationId) {
        return jdbc.query("SELECT id,role,content,created_at FROM message WHERE conversation_id=:id ORDER BY created_at,id",
                Map.of("id", conversationId), (rs,row) -> new HistoryMessage(rs.getString("id"), rs.getString("role"),
                        rs.getString("content"), rs.getTimestamp("created_at").toInstant()));
    }

    private final NamedParameterJdbcTemplate jdbc;

    public ConversationRepository(@Qualifier("primaryNamedParameterJdbcTemplate") NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String create(String agentVersionId) {
        var id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO conversation (id, agent_version_id, created_at) VALUES (:id, :versionId, :createdAt)",
                Map.of("id", id, "versionId", agentVersionId, "createdAt", Timestamp.from(Instant.now())));
        return id;
    }

    public Optional<String> findAgentVersionId(String conversationId) {
        return jdbc.query("SELECT agent_version_id FROM conversation WHERE id = :id AND deleted_at IS NULL", Map.of("id", conversationId),
                (rs, rowNumber) -> rs.getString("agent_version_id")).stream().findFirst();
    }

    @org.springframework.transaction.annotation.Transactional
    public void moveToTrash(List<String> ids, boolean deleted) {
        if (ids == null || ids.isEmpty() || ids.size() > 200 || ids.stream().anyMatch(id -> id == null || id.isBlank()))
            throw new com.agentstudio.system.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,"请选择 1 至 200 条会话");
        for (var id : ids.stream().distinct().toList()) {
            var params = new java.util.HashMap<String,Object>();
            params.put("id",id);
            params.put("deletedAt",deleted ? Timestamp.from(Instant.now()) : null);
            if (jdbc.queryForObject("SELECT COUNT(*) FROM conversation WHERE id=:id",params,Integer.class) == 0)
                throw new com.agentstudio.system.ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"会话不存在");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM agent_run WHERE conversation_id=:id AND status NOT IN ('COMPLETED','FAILED','CANCELLED','TIMED_OUT','INTERRUPTED')",params,Integer.class) > 0)
                throw new com.agentstudio.system.ApiException(org.springframework.http.HttpStatus.CONFLICT,"会话仍在执行，请等待结束后再删除或恢复");
            jdbc.update("UPDATE conversation SET deleted_at=:deletedAt WHERE id=:id",params);
        }
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

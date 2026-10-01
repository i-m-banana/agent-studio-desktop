package com.agentstudio.execution;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public AuditRepository(@Qualifier("primaryNamedParameterJdbcTemplate") NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AuditEvent add(String runId, String conversationId, String agentVersionId, String eventType,
                          String toolName, String capability, String riskLevel, String status,
                          String argumentsSha256, String details) {
        var event = new AuditEvent(UUID.randomUUID().toString(), runId, conversationId, agentVersionId,
                eventType, toolName, capability, riskLevel, status, argumentsSha256,
                truncate(details), Instant.now());
        jdbc.update("""
                INSERT INTO audit_event
                  (id, run_id, conversation_id, agent_version_id, event_type, tool_name, capability,
                   risk_level, status, arguments_sha256, details, created_at)
                VALUES
                  (:id, :runId, :conversationId, :agentVersionId, :eventType, :toolName, :capability,
                   :riskLevel, :status, :argumentsSha256, :details, :createdAt)
                """, new MapSqlParameterSource()
                .addValue("id", event.id()).addValue("runId", event.runId())
                .addValue("conversationId", event.conversationId()).addValue("agentVersionId", event.agentVersionId())
                .addValue("eventType", event.eventType()).addValue("toolName", event.toolName())
                .addValue("capability", event.capability()).addValue("riskLevel", event.riskLevel())
                .addValue("status", event.status()).addValue("argumentsSha256", event.argumentsSha256())
                .addValue("details", event.details()).addValue("createdAt", Timestamp.from(event.createdAt())));
        return event;
    }

    public List<AuditEvent> list(String runId, int limit) {
        return jdbc.query("""
                SELECT * FROM audit_event WHERE run_id=:runId ORDER BY created_at, id LIMIT :limit
                """, Map.of("runId", runId, "limit", Math.max(1, Math.min(limit, 200))), (rs, row) ->
                new AuditEvent(rs.getString("id"), rs.getString("run_id"), rs.getString("conversation_id"),
                        rs.getString("agent_version_id"), rs.getString("event_type"), rs.getString("tool_name"),
                        rs.getString("capability"), rs.getString("risk_level"), rs.getString("status"),
                        rs.getString("arguments_sha256"), rs.getString("details"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    static String truncate(String value) {
        if (value == null) return null;
        if (value.length() <= 1000) return value;
        // The ellipsis counts toward VARCHAR(1000); never split a UTF-16 surrogate pair.
        int end = 999;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + "…";
    }
}

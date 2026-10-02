package com.agentstudio.approval;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ApprovalRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ApprovalRepository(@Qualifier("primaryNamedParameterJdbcTemplate") NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ApprovalRequest request) {
        jdbc.update("""
                INSERT INTO approval_request
                  (id, run_id, conversation_id, agent_version_id, tool_call_id, tool_name,
                   capability, risk_level, target_environment, arguments_json, arguments_sha256,
                   status, reason, created_at, expires_at, decided_at)
                VALUES
                  (:id, :runId, :conversationId, :agentVersionId, :toolCallId, :toolName,
                   :capability, :riskLevel, :targetEnvironment, :argumentsJson, :argumentsSha256,
                   :status, :reason, :createdAt, :expiresAt, :decidedAt)
                """, parameters(request));
    }

    public Optional<ApprovalRequest> find(String id) {
        return jdbc.query("SELECT * FROM approval_request WHERE id = :id", Map.of("id", id),
                ApprovalRepository::map).stream().findFirst();
    }

    public List<ApprovalRequest> forToolCall(String runId, String toolCallId) {
        return jdbc.query("SELECT * FROM approval_request WHERE run_id=:runId AND tool_call_id=:callId ORDER BY created_at,id",
                Map.of("runId",runId,"callId",toolCallId), ApprovalRepository::map);
    }

    public boolean decide(String id, String status, String reason, Instant decidedAt) {
        return jdbc.update("""
                UPDATE approval_request SET status=:status, reason=:reason, decided_at=:decidedAt
                WHERE id=:id AND status='PENDING'
                """, new MapSqlParameterSource().addValue("id", id).addValue("status", status)
                .addValue("reason", reason).addValue("decidedAt", Timestamp.from(decidedAt))) == 1;
    }

    public boolean consume(String id, String argumentsSha256) {
        return jdbc.update("""
                UPDATE approval_request SET status='CONSUMED'
                WHERE id=:id AND status='APPROVED' AND arguments_sha256=:argumentsSha256
                """, Map.of("id", id, "argumentsSha256", argumentsSha256)) == 1;
    }

    public List<ApprovalRequest> findAllPending() {
        return jdbc.query("SELECT * FROM approval_request WHERE status='PENDING'", ApprovalRepository::map);
    }

    public List<ApprovalRequest> findPendingByRun(String runId) {
        return jdbc.query("SELECT * FROM approval_request WHERE run_id=:runId AND status='PENDING'",
                Map.of("runId", runId), ApprovalRepository::map);
    }

    private MapSqlParameterSource parameters(ApprovalRequest request) {
        return new MapSqlParameterSource().addValue("id", request.id()).addValue("runId", request.runId())
                .addValue("conversationId", request.conversationId())
                .addValue("agentVersionId", request.agentVersionId())
                .addValue("toolCallId", request.toolCallId()).addValue("toolName", request.toolName())
                .addValue("capability", request.capability()).addValue("riskLevel", request.riskLevel())
                .addValue("targetEnvironment", request.targetEnvironment())
                .addValue("argumentsJson", request.argumentsJson()).addValue("argumentsSha256", request.argumentsSha256())
                .addValue("status", request.status()).addValue("reason", request.reason())
                .addValue("createdAt", Timestamp.from(request.createdAt()))
                .addValue("expiresAt", Timestamp.from(request.expiresAt())).addValue("decidedAt", null);
    }

    private static ApprovalRequest map(ResultSet rs, int row) throws SQLException {
        return new ApprovalRequest(rs.getString("id"), rs.getString("run_id"), rs.getString("conversation_id"),
                rs.getString("agent_version_id"), rs.getString("tool_call_id"), rs.getString("tool_name"),
                rs.getString("capability"), rs.getString("risk_level"), rs.getString("target_environment"),
                rs.getString("arguments_json"), rs.getString("arguments_sha256"),
                rs.getString("status"), rs.getString("reason"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(), instant(rs.getTimestamp("decided_at")));
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}

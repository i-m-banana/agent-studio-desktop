package com.agentstudio.runtime;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RunRepository {

    private static final RowMapper<RunStep> STEP_MAPPER = RunRepository::mapStep;
    private final NamedParameterJdbcTemplate jdbc;
    private final com.agentstudio.release.ReleaseTaskRepository releaseTasks;

    public RunRepository(@Qualifier("primaryNamedParameterJdbcTemplate") NamedParameterJdbcTemplate jdbc,
                         com.agentstudio.release.ReleaseTaskRepository releaseTasks) {
        this.jdbc = jdbc;
        this.releaseTasks = releaseTasks;
    }

    public AgentRun start(String conversationId, String agentVersionId) {
        var run = new AgentRun(UUID.randomUUID().toString(), conversationId, agentVersionId,
                "CREATED", Instant.now(), null, null, List.of());
        jdbc.update("""
                INSERT INTO agent_run (id, conversation_id, agent_version_id, status, started_at)
                VALUES (:id, :conversationId, :agentVersionId, :status, :startedAt)
                """, new MapSqlParameterSource()
                .addValue("id", run.id()).addValue("conversationId", run.conversationId())
                .addValue("agentVersionId", run.agentVersionId()).addValue("status", run.status())
                .addValue("startedAt", Timestamp.from(run.startedAt())));
        return run;
    }

    @org.springframework.transaction.annotation.Transactional
    public RunStep addStep(String runId, String stepType, String status, String toolCallId,
                           String toolName, String inputJson, String outputText, Long durationMs) {
        var step = new RunStep(UUID.randomUUID().toString(), runId, nextStepNumber(runId),
                stepType, status, toolCallId, toolName, inputJson, outputText, durationMs, Instant.now());
        jdbc.update("""
                INSERT INTO run_step
                    (id, run_id, step_number, step_type, status, tool_call_id, tool_name,
                     input_json, output_text, duration_ms, created_at)
                VALUES
                    (:id, :runId, :stepNumber, :stepType, :status, :toolCallId, :toolName,
                     :inputJson, :outputText, :durationMs, :createdAt)
                """, stepParameters(step));
        if ("TOOL_CALL".equals(stepType)) releaseTasks.capture(step.id(), runId, toolCallId, toolName);
        return step;
    }

    public boolean finish(String runId, String status, String errorMessage) {
        var termination = switch (status) {
            case "CANCELLED", "TIMED_OUT", "INTERRUPTED" -> true;
            default -> false;
        };
        var guard = termination
                ? "status NOT IN ('CANCELLED', 'TIMED_OUT', 'INTERRUPTED', 'COMPLETED', 'FAILED')"
                : "status NOT IN ('CANCEL_REQUESTED', 'CANCELLED', 'TIMED_OUT', 'INTERRUPTED', 'COMPLETED', 'FAILED')";
        return jdbc.update("""
                UPDATE agent_run SET status = :status, completed_at = :completedAt, error_message = :errorMessage
                WHERE id = :id AND %s
                """.formatted(guard), new MapSqlParameterSource().addValue("id", runId).addValue("status", status)
                .addValue("completedAt", Timestamp.from(Instant.now())).addValue("errorMessage", errorMessage)) == 1;
    }

    public void updateStatus(String runId, String status) {
        jdbc.update("""
                UPDATE agent_run SET status = :status WHERE id = :id
                AND status NOT IN ('CANCEL_REQUESTED', 'CANCELLED', 'TIMED_OUT', 'INTERRUPTED', 'COMPLETED', 'FAILED')
                """,
                Map.of("id", runId, "status", status));
    }

    public boolean requestCancellation(String runId) {
        return jdbc.update("""
                UPDATE agent_run SET status='CANCEL_REQUESTED'
                WHERE id=:id AND status NOT IN ('CANCELLED', 'TIMED_OUT', 'INTERRUPTED', 'COMPLETED', 'FAILED')
                """, Map.of("id", runId)) == 1;
    }

    public int interruptUnfinished(String reason) {
        return jdbc.update("""
                UPDATE agent_run SET status='INTERRUPTED', completed_at=:completedAt, error_message=:reason
                WHERE status NOT IN ('CANCELLED', 'TIMED_OUT', 'INTERRUPTED', 'COMPLETED', 'FAILED')
                """, new MapSqlParameterSource().addValue("completedAt", Timestamp.from(Instant.now()))
                .addValue("reason", reason));
    }

    public List<RunSummary> list(int limit) {
        return list(limit, 0, "", "");
    }

    public List<AgentRun> unfinished() {
        return jdbc.query("""
                SELECT id FROM agent_run
                WHERE status NOT IN ('CANCELLED','TIMED_OUT','INTERRUPTED','COMPLETED','FAILED')
                ORDER BY started_at,id
                """, Map.of(), (rs, row) -> rs.getString("id")).stream()
                .map(id -> find(id).orElseThrow()).toList();
    }

    public List<RunSummary> list(int limit, int offset, String query, String status) {
        var search = com.agentstudio.system.ListSearch.normalize(query);
        var filter = status == null ? "" : status.strip();
        if (!java.util.Set.of("", "CREATED", "RUNNING", "THINKING", "TOOL_RUNNING", "OBSERVING", "WAITING_APPROVAL", "CANCEL_REQUESTED", "COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT", "INTERRUPTED").contains(filter))
            throw new com.agentstudio.system.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "运行状态筛选无效");
        return jdbc.query("""
                SELECT r.*, a.name AS agent_name, (SELECT COUNT(*) FROM run_step s WHERE s.run_id=r.id) AS step_count,
                  (SELECT SUBSTRING(m.content,1,100) FROM message m WHERE m.conversation_id=r.conversation_id AND m.role='user' AND m.created_at<=r.started_at
                    ORDER BY m.created_at DESC,m.id DESC LIMIT 1) AS preview
                FROM agent_run r JOIN agent_version v ON v.id=r.agent_version_id
                  JOIN agent_definition a ON a.id=v.agent_definition_id
                WHERE (:status='' OR r.status=:status OR (:status='RUNNING' AND r.status IN ('CREATED','THINKING','TOOL_RUNNING','OBSERVING','CANCEL_REQUESTED')))
                AND (:query='' OR LOCATE(:query,LOWER(r.id))>0
                  OR LOCATE(:query,LOWER(a.name))>0 OR LOCATE(:query,LOWER(COALESCE(r.error_message,'')))>0
                  OR LOCATE(:query,LOWER(COALESCE((SELECT m.content FROM message m WHERE m.conversation_id=r.conversation_id AND m.role='user' AND m.created_at<=r.started_at
                    ORDER BY m.created_at DESC,m.id DESC LIMIT 1),'')))>0
                  OR EXISTS (SELECT 1 FROM run_step s WHERE s.run_id=r.id AND LOCATE(:query,LOWER(COALESCE(s.tool_name,'')))>0))
                ORDER BY r.started_at DESC,r.id DESC LIMIT :limit OFFSET :offset
                """, Map.of("limit", Math.max(1, Math.min(limit, 100)), "offset", Math.max(0, offset), "query", search, "status", filter), (rs, row) ->
                new RunSummary(rs.getString("id"), rs.getString("conversation_id"),
                        rs.getString("agent_version_id"), rs.getString("status"),
                        rs.getTimestamp("started_at").toInstant(), instant(rs, "completed_at"),
                        rs.getString("error_message"), rs.getInt("step_count"), rs.getString("preview"), rs.getString("agent_name")));
    }

    public Optional<AgentRun> find(String runId) {
        return jdbc.query("SELECT * FROM agent_run WHERE id = :id", Map.of("id", runId), (rs, row) ->
                new AgentRun(rs.getString("id"), rs.getString("conversation_id"),
                        rs.getString("agent_version_id"), rs.getString("status"),
                        rs.getTimestamp("started_at").toInstant(), instant(rs, "completed_at"),
                        rs.getString("error_message"), steps(runId))).stream().findFirst();
    }

    public List<AgentRun> forConversation(String conversationId) {
        return jdbc.query("SELECT id FROM agent_run WHERE conversation_id=:id ORDER BY started_at,id",
                Map.of("id",conversationId), (rs,row) -> rs.getString("id")).stream()
                .map(id -> find(id).orElseThrow()).toList();
    }

    private List<RunStep> steps(String runId) {
        return jdbc.query("SELECT * FROM run_step WHERE run_id = :runId ORDER BY step_number",
                Map.of("runId", runId), STEP_MAPPER);
    }

    private int nextStepNumber(String runId) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(step_number), 0) + 1 FROM run_step WHERE run_id = :runId",
                Map.of("runId", runId), Integer.class);
    }

    private MapSqlParameterSource stepParameters(RunStep step) {
        return new MapSqlParameterSource().addValue("id", step.id()).addValue("runId", step.runId())
                .addValue("stepNumber", step.stepNumber()).addValue("stepType", step.stepType())
                .addValue("status", step.status()).addValue("toolCallId", step.toolCallId())
                .addValue("toolName", step.toolName()).addValue("inputJson", step.inputJson())
                .addValue("outputText", step.outputText()).addValue("durationMs", step.durationMs())
                .addValue("createdAt", Timestamp.from(step.createdAt()));
    }

    private static RunStep mapStep(ResultSet rs, int row) throws SQLException {
        var duration = rs.getObject("duration_ms", Long.class);
        return new RunStep(rs.getString("id"), rs.getString("run_id"), rs.getInt("step_number"),
                rs.getString("step_type"), rs.getString("status"), rs.getString("tool_call_id"),
                rs.getString("tool_name"), rs.getString("input_json"), rs.getString("output_text"),
                duration, rs.getTimestamp("created_at").toInstant());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}

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

    public RunRepository(@Qualifier("primaryNamedParameterJdbcTemplate") NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        return step;
    }

    public void finish(String runId, String status, String errorMessage) {
        jdbc.update("""
                UPDATE agent_run SET status = :status, completed_at = :completedAt, error_message = :errorMessage
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", runId).addValue("status", status)
                .addValue("completedAt", Timestamp.from(Instant.now())).addValue("errorMessage", errorMessage));
    }

    public void updateStatus(String runId, String status) {
        jdbc.update("UPDATE agent_run SET status = :status WHERE id = :id",
                Map.of("id", runId, "status", status));
    }

    public Optional<AgentRun> find(String runId) {
        return jdbc.query("SELECT * FROM agent_run WHERE id = :id", Map.of("id", runId), (rs, row) ->
                new AgentRun(rs.getString("id"), rs.getString("conversation_id"),
                        rs.getString("agent_version_id"), rs.getString("status"),
                        rs.getTimestamp("started_at").toInstant(), instant(rs, "completed_at"),
                        rs.getString("error_message"), steps(runId))).stream().findFirst();
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

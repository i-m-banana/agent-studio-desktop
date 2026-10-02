package com.agentstudio.release;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable business identities for atomic release stages; never an execution entry point. */
@Repository
public class ReleaseTaskRepository {
    public static final Set<String> TOOLS = Set.of("prepare_release_candidate", "build_release_candidate_image",
            "prepare_remote_deployment_backup", "inspect_remote_deployment", "adopt_remote_database_baseline",
            "verify_remote_deployment_backup_restore", "publish_remote_release");
    private final NamedParameterJdbcTemplate jdbc;

    public ReleaseTaskRepository(@Qualifier("primaryNamedParameterJdbcTemplate") NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Identity(String id, String sourceStepId, String runId, String conversationId,
                           String agentVersionId, String toolCallId, String toolName) {}

    public void capture(String stepId, String runId, String callId, String toolName) {
        if (callId == null || toolName == null || !TOOLS.contains(toolName)) return;
        try {
            jdbc.update("""
                    INSERT INTO release_task
                      (id, source_step_id, run_id, conversation_id, agent_version_id, tool_call_id, tool_name, created_at)
                    SELECT :id, s.id, r.id, r.conversation_id, r.agent_version_id, s.tool_call_id, s.tool_name, s.created_at
                    FROM run_step s JOIN agent_run r ON r.id=s.run_id WHERE s.id=:stepId
                    """, Map.of("id", UUID.randomUUID().toString(), "stepId", stepId));
        } catch (DuplicateKeyException alreadyCaptured) {
            // Startup backfill and a live capture can race; unique source identity makes this idempotent.
        }
    }

    public void backfill() {
        var missing = jdbc.query("""
                SELECT s.id, s.run_id, s.tool_call_id, s.tool_name FROM run_step s
                LEFT JOIN release_task t ON t.source_step_id=s.id
                WHERE s.step_type='TOOL_CALL' AND s.tool_call_id IS NOT NULL AND t.id IS NULL
                AND s.tool_name IN (:tools)
                """, Map.of("tools", TOOLS), (rs, row) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
        missing.forEach(s -> capture(s[0], s[1], s[2], s[3]));
    }

    public List<Identity> list(String conversationId, int limit, int offset) {
        return jdbc.query("""
                SELECT * FROM release_task WHERE (:conversationId IS NULL OR conversation_id=:conversationId)
                ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset
                """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("conversationId", conversationId).addValue("limit", Math.max(1, Math.min(limit, 200)))
                .addValue("offset", Math.max(0,offset)),
                (rs, row) -> new Identity(rs.getString("id"), rs.getString("source_step_id"), rs.getString("run_id"),
                        rs.getString("conversation_id"), rs.getString("agent_version_id"), rs.getString("tool_call_id"), rs.getString("tool_name")));
    }
}

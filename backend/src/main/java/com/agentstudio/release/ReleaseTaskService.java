package com.agentstudio.release;

import java.time.Instant;
import java.util.List;

import com.agentstudio.execution.AuditEvent;
import com.agentstudio.execution.AuditRepository;
import com.agentstudio.runtime.RunRepository;
import com.agentstudio.runtime.RunStep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class ReleaseTaskService {
    private final ReleaseTaskRepository tasks;
    private final RunRepository runs;
    private final AuditRepository audits;
    private final ObjectMapper json;
    private final com.agentstudio.approval.ApprovalRepository approvals;

    public ReleaseTaskService(ReleaseTaskRepository tasks, RunRepository runs, AuditRepository audits, ObjectMapper json,
                              com.agentstudio.approval.ApprovalRepository approvals) {
        this.tasks = tasks; this.runs = runs; this.audits = audits; this.json = json;
        this.approvals = approvals;
    }

    public record Task(String id, String runId, String conversationId, String agentVersionId, String toolName,
                       String status, Instant createdAt, Instant observedAt, String target, String releaseId,
                       JsonNode receipt, String rawReceipt, RunStep sourceStep, List<AuditEvent> auditEvents,
                       List<com.agentstudio.approval.ApprovalRequest> approvals,
                       String nextAction) {}

    public List<Task> list(String conversationId, int limit) {
        return list(conversationId,limit,0);
    }
    public List<Task> list(String conversationId, int limit, int offset) {
        return tasks.list(conversationId, limit, offset).stream().map(this::project).toList();
    }

    private Task project(ReleaseTaskRepository.Identity identity) {
        var run = runs.find(identity.runId()).orElseThrow();
        var source = run.steps().stream().filter(s -> s.id().equals(identity.sourceStepId())).findFirst().orElseThrow();
        var result = run.steps().stream().filter(s -> identity.toolCallId().equals(s.toolCallId())
                && "TOOL_RESULT".equals(s.stepType())).reduce((a, b) -> b).orElse(null);
        var events = audits.list(run.id(), 200).stream().filter(e -> identity.toolName().equals(e.toolName())
                && e.createdAt().compareTo(source.createdAt()) >= 0).toList();
        // AuditEvent has no call ID. Correlate by the exact immutable argument hash and call interval.
        var nextCallAt = run.steps().stream().filter(s -> "TOOL_CALL".equals(s.stepType())
                && s.stepNumber() > source.stepNumber()).map(RunStep::createdAt).findFirst().orElse(Instant.MAX);
        var hash = sha256(source.inputJson() == null ? "{}" : source.inputJson());
        events = events.stream().filter(e -> hash.equals(e.argumentsSha256()) && e.createdAt().isBefore(nextCallAt)).toList();
        JsonNode receipt = null;
        if (result != null && "COMPLETED".equals(result.status())) {
            try { var parsed = json.readTree(result.outputText()); if (parsed != null && parsed.isObject()) receipt = parsed; }
            catch (Exception malformed) { /* Historical truncation is not evidence of success. */ }
        }
        var started = events.stream().anyMatch(e -> "TOOL_EXECUTION_STARTED".equals(e.eventType()));
        var completed = events.stream().anyMatch(e -> "TOOL_EXECUTION_COMPLETED".equals(e.eventType()));
        var skipped = events.stream().anyMatch(e -> "TOOL_EXECUTION_SKIPPED".equals(e.eventType()));
        var approved = events.stream().anyMatch(e -> "APPROVAL_DECIDED".equals(e.eventType()) && "APPROVED".equals(e.status()));
        var terminal = java.util.Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT", "INTERRUPTED").contains(run.status());
        String status;
        if (receipt != null && completed && approved) status = receiptStatus(identity.toolName(), receipt);
        else if (skipped) status = "NOT_EXECUTED";
        else if (terminal) status = started || result != null ? "UNKNOWN" : "NOT_EXECUTED";
        else status = started ? "RUNNING" : "WAITING";
        var action = switch (status) {
            case "DEPLOYED" -> "历史上线回执已确认；需要了解当前网站时，请重新读取线上版本和站点健康。";
            case "RESTORED" -> "旧应用恢复回执已确认；数据库扩展可能保留，先重新核查线上版本、历史和健康。";
            case "UNKNOWN", "MANUAL_INTERVENTION" -> "停止重试；先核查线上版本、数据库版本历史和站点健康，再人工处理。";
            case "RUNNING", "WAITING" -> "观察原运行；刷新不会续执行或重新批准。";
            case "SUCCEEDED" -> "这是历史阶段证据；后续动作仍需独立审批，并重新核查有时效的证据。";
            case "INCOMPLETE_EVIDENCE" -> "原回执报告成功，但缺少当前流程所需证据；查看来源，并以新的受审任务补齐验证。";
            default -> "本阶段未成功；查看原始回执和审计，处理原因后再发起新的受审任务。";
        };
        JsonNode input = null;
        try { input = json.readTree(source.inputJson()); } catch (Exception ignored) { /* No identity inferred from prose. */ }
        var releaseId = text(receipt,"releaseId");
        if (releaseId == null) releaseId = text(input,"releaseId");
        return new Task(identity.id(), run.id(), run.conversationId(), run.agentVersionId(), identity.toolName(), status,
                source.createdAt(), result == null ? source.createdAt() : result.createdAt(), text(receipt, "target"),
                releaseId, receipt, result == null ? null : result.outputText(), source, events,
                approvals.forToolCall(run.id(),identity.toolCallId()), action);
    }

    static String receiptStatus(String tool, JsonNode receipt) {
        boolean ok = receipt.path("successful").isBoolean() && receipt.path("successful").asBoolean()
                && receipt.path("exitCode").isIntegralNumber() && receipt.path("exitCode").asInt(-1) == 0;
        if ("publish_remote_release".equals(tool)) {
            if (ok && receipt.path("deployed").asBoolean() && receipt.path("rolledBack").isBoolean()
                    && !receipt.path("rolledBack").asBoolean() && receipt.path("manualInterventionRequired").isBoolean()
                    && !receipt.path("manualInterventionRequired").asBoolean()) return "DEPLOYED";
            if (!ok && receipt.path("deployed").isBoolean() && !receipt.path("deployed").asBoolean()
                    && receipt.path("rolledBack").asBoolean() && receipt.path("manualInterventionRequired").isBoolean()
                    && !receipt.path("manualInterventionRequired").asBoolean()) return "RESTORED";
            return "MANUAL_INTERVENTION";
        }
        if (ok && "prepare_release_candidate".equals(tool) && !("REMOTE_VERIFY".equals(text(receipt, "stage"))
                && text(receipt, "releaseId") != null && text(receipt, "manifestSha256") != null)) return "INCOMPLETE_EVIDENCE";
        if (ok && "build_release_candidate_image".equals(tool) && !("IMAGE_READY".equals(text(receipt, "stage"))
                && receipt.path("output").asText().contains("AGENTSTUDIO_STAGE=RUNTIME_SMOKE"))) return "INCOMPLETE_EVIDENCE";
        if ("DATABASE_SCHEMA".equals(text(receipt, "task"))) ok &= receipt.path("schemaComplete").asBoolean()
                && !receipt.path("outputTruncated").asBoolean();
        if ("adopt_remote_database_baseline".equals(tool)) ok &= receipt.path("baselineRegistered").asBoolean();
        return ok ? "SUCCEEDED" : "FAILED";
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.path(field).isTextual() && !node.path(field).asText().isBlank() ? node.path(field).asText() : null;
    }
    private static String sha256(String input) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}

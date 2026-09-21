package com.agentstudio.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import com.agentstudio.approval.ApprovalService;
import com.agentstudio.model.ModelToolCall;
import com.agentstudio.runtime.RunTerminatedException;
import com.agentstudio.tool.ToolRegistry;
import org.springframework.stereotype.Service;

@Service
public class SafeExecutionGateway {
    private final ToolRegistry tools;
    private final ToolInputValidator validator;
    private final ApprovalService approvals;
    private final AuditRepository audits;

    public SafeExecutionGateway(ToolRegistry tools, ToolInputValidator validator,
                                ApprovalService approvals, AuditRepository audits) {
        this.tools = tools;
        this.validator = validator;
        this.approvals = approvals;
        this.audits = audits;
    }

    public SafeExecutionResult execute(String runId, String conversationId, String agentVersionId,
                                       ModelToolCall call, ApprovalListener approvalListener,
                                       Runnable executionStarted, Runnable cancellationCheck) throws Exception {
        var descriptor = tools.descriptor(call.name());
        var argumentsHash = sha256(call.argumentsJson() == null ? "{}" : call.argumentsJson());
        try {
            validator.validate(descriptor, call.argumentsJson());
        } catch (RuntimeException exception) {
            audit(runId, conversationId, agentVersionId, "TOOL_REQUEST_REJECTED", descriptor,
                    "INVALID_ARGUMENTS", argumentsHash, exception.getMessage());
            throw exception;
        }
        audit(runId, conversationId, agentVersionId, "TOOL_REQUEST_VALIDATED", descriptor,
                "ACCEPTED", argumentsHash, "参数通过 Schema 校验");

        com.agentstudio.approval.ApprovalRequest approval = null;
        com.agentstudio.approval.ApprovalOutcome outcome = null;
        if ("HIGH".equals(descriptor.riskLevel())) {
            approval = approvals.request(runId, conversationId, agentVersionId, call, descriptor,
                    tools.targetEnvironment(call.name()));
            audit(runId, conversationId, agentVersionId, "APPROVAL_REQUIRED", descriptor,
                    "WAITING", argumentsHash, "等待参数绑定的一次性审批");
            approvalListener.requested(approval);
            try {
                outcome = approvals.await(approval);
                cancellationCheck.run();
            } catch (Exception exception) {
                try {
                    cancellationCheck.run();
                } catch (RunTerminatedException terminated) {
                    audit(runId, conversationId, agentVersionId, "APPROVAL_WAIT_TERMINATED", descriptor,
                            terminated.termination().status(), argumentsHash, terminated.getMessage());
                    throw terminated;
                }
                throw exception;
            }
            audit(runId, conversationId, agentVersionId, "APPROVAL_DECIDED", descriptor,
                    outcome.status(), argumentsHash, outcome.reason());
            if (!outcome.approved()) {
                var denied = "工具未执行：审批" + ("EXPIRED".equals(outcome.status()) ? "已过期" : "被拒绝")
                        + (outcome.reason() == null ? "" : "（" + outcome.reason() + "）");
                audit(runId, conversationId, agentVersionId, "TOOL_EXECUTION_SKIPPED", descriptor,
                        outcome.status(), argumentsHash, denied);
                return new SafeExecutionResult(false, denied, null, approval, outcome);
            }
            var currentTarget = tools.targetEnvironment(call.name());
            if (!approval.targetEnvironment().equals(currentTarget)) {
                var changed = "审批目标与当前执行目标不一致，工具未执行";
                audit(runId, conversationId, agentVersionId, "TOOL_TARGET_CHANGED", descriptor,
                        "REJECTED", argumentsHash, changed);
                throw new IllegalStateException(changed);
            }
        }

        cancellationCheck.run();
        executionStarted.run();
        audit(runId, conversationId, agentVersionId, "TOOL_EXECUTION_STARTED", descriptor,
                "RUNNING", argumentsHash, "执行入口：SafeExecutionGateway");
        try {
            var result = tools.execute(call.name(), call.argumentsJson());
            cancellationCheck.run();
            audit(runId, conversationId, agentVersionId, "TOOL_EXECUTION_COMPLETED", descriptor,
                    "COMPLETED", argumentsHash, "输出字符数=" + (result.output() == null ? 0 : result.output().length())
                            + "，耗时=" + result.durationMs() + "ms");
            return new SafeExecutionResult(true, result.output(), result.durationMs(), approval, outcome);
        } catch (RunTerminatedException exception) {
            audit(runId, conversationId, agentVersionId, "TOOL_EXECUTION_TERMINATED", descriptor,
                    exception.termination().status(), argumentsHash, exception.getMessage());
            throw exception;
        } catch (Exception exception) {
            audit(runId, conversationId, agentVersionId, "TOOL_EXECUTION_FAILED", descriptor,
                    "FAILED", argumentsHash, safeMessage(exception));
            throw exception;
        }
    }

    private void audit(String runId, String conversationId, String agentVersionId, String type,
                       com.agentstudio.tool.ToolDescriptor descriptor, String status,
                       String argumentsHash, String details) {
        audits.add(runId, conversationId, agentVersionId, type, descriptor.name(), descriptor.capability(),
                descriptor.riskLevel(), status, argumentsHash, details);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}

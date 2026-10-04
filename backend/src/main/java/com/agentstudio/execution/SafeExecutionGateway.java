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
    @org.springframework.beans.factory.annotation.Autowired
    private com.agentstudio.project.LocalProjectService projects;
    @org.springframework.beans.factory.annotation.Autowired
    private com.agentstudio.release.DeploymentOperationGuard deploymentGuard;

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
        var project = projects == null ? null : projects.resolve(conversationId, null);
        if(project!=null&&deploymentGuard!=null&&java.util.Set.of("apply_workspace_text_patch","create_workspace_text_file").contains(call.name()))deploymentGuard.requireMutableProject(project.id());
        if (projects != null && project == null && (java.util.Set.of("list_workspace_directory","search_workspace_files","read_workspace_text_file","apply_workspace_text_patch","create_workspace_text_file","run_workspace_verification","start_project_preview").contains(call.name()) || call.name().equals("prepare_release_candidate")))
            throw new IllegalArgumentException("文件工具需要会话绑定具体本地项目");
        if (project != null && (call.name().startsWith("mcp_") || call.name().equals("write_workspace_note")))
            throw new IllegalArgumentException("本地项目模式不允许绕过工作区的外部 MCP 或笔记写入工具");
        try (var lease = deploymentGuard == null ? (AutoCloseable)()->{} : deploymentGuard.enter(runId,call);
             var context = com.agentstudio.project.ProjectExecutionContext.enter(project)) {
            return executeBound(runId, conversationId, agentVersionId, call, approvalListener, executionStarted, cancellationCheck);
        }
    }

    private String boundTarget(ModelToolCall call) throws Exception {
        var name=call.name();
        var target = tools.targetEnvironment(name);
        var project = com.agentstudio.project.ProjectExecutionContext.current();
        if (project == null) return target;
        var identity = projects.identity(project);
        // Publication remains tied to the same project snapshot across the approval wait.
        if (name.equals("prepare_release_candidate")) {
            var profile = deploymentProfiles.current();
            if (!java.nio.file.Path.of(profile.localSourceRoot()).toAbsolutePath().normalize().equals(java.nio.file.Path.of(project.sourceRoot())))
                throw new IllegalArgumentException("编码与发布源码来源不一致，请核对项目和部署设置");
        }
        String source="";
        if(name.equals("create_workspace_text_file") || name.equals("apply_workspace_text_patch")) {
            var args=new com.fasterxml.jackson.databind.ObjectMapper().readTree(call.argumentsJson());
            var relative=com.agentstudio.coding.CodingWorkspace.safeRelative(args.path("path").asText());
            var parent=codingWorkspace.requireDirectory(relative.getParent()==null ? "." : relative.getParent().toString());
            var attributes=java.nio.file.Files.readAttributes(parent,java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);
            source="|FILE:"+relative+"|PARENT:"+parent+"#"+com.agentstudio.coding.CodingWorkspace.directoryIdentity(parent)+"#"+attributes.creationTime();
            codingWorkspace.requireWritable(codingWorkspace.root().resolve(relative));
            if(name.equals("create_workspace_text_file") && java.nio.file.Files.exists(codingWorkspace.root().resolve(relative),java.nio.file.LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("新建目标已存在，不能覆盖");
        }
        if(name.equals("prepare_release_candidate") || name.equals("run_workspace_verification") || name.equals("start_project_preview"))
            source="|SOURCE_SHA256:"+com.agentstudio.coding.ProjectSourceSnapshot.fingerprint(codingWorkspace)+"|"+isolatedRunner.identity();
        if(name.equals("run_workspace_verification")&&new com.fasterxml.jackson.databind.ObjectMapper().readTree(call.argumentsJson()).path("task").asText().equals("MYSQL_INTEGRATION"))source=source.replace("|NETWORK:none","|NETWORK:owned-internal-isolated")+"|"+mysqlRunner.identity();
        return "PROJECT:" + project.name() + "|ID:" + project.id() + "|REV:" + project.revision()
                + "|WORKSPACE:" + identity + "|TARGET:" + target + source;
    }
    @org.springframework.beans.factory.annotation.Autowired
    private com.agentstudio.adapter.ssh.RemoteDeploymentService deploymentProfiles;
    @org.springframework.beans.factory.annotation.Autowired private com.agentstudio.coding.CodingWorkspace codingWorkspace;
    @org.springframework.beans.factory.annotation.Autowired private com.agentstudio.coding.IsolatedProjectRunner isolatedRunner;
    @org.springframework.beans.factory.annotation.Autowired private com.agentstudio.coding.MySqlVerificationRunner mysqlRunner;

    private SafeExecutionResult executeBound(String runId, String conversationId, String agentVersionId,
                                       ModelToolCall call, ApprovalListener approvalListener,
                                       Runnable executionStarted, Runnable cancellationCheck) throws Exception {
        var descriptor = tools.descriptor(call.name());
        var argumentsHash = sha256(call.argumentsJson() == null ? "{}" : call.argumentsJson());
        try {
            validator.validate(descriptor, call.argumentsJson());
            if(call.name().equals("create_workspace_text_file"))
                com.agentstudio.coding.CreateWorkspaceTextFileTool.validatePath(new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(call.argumentsJson()).path("path").asText());
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
                    boundTarget(call));
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
            if (projects != null) projects.resolve(conversationId, null);
            var currentTarget = boundTarget(call);
            if(deploymentGuard!=null)deploymentGuard.validate(runId,call);
            if (!approval.targetEnvironment().equals(currentTarget)) {
                var changed = "审批目标与当前执行目标不一致，工具未执行";
                audit(runId, conversationId, agentVersionId, "TOOL_TARGET_CHANGED", descriptor,
                        "REJECTED", argumentsHash, changed);
                throw new IllegalStateException(changed);
            }
            var sourceMatcher=java.util.regex.Pattern.compile("SOURCE_SHA256:([0-9a-f]{64})").matcher(approval.targetEnvironment());
            if(sourceMatcher.find())com.agentstudio.project.ProjectExecutionContext.bindSourceSha256(sourceMatcher.group(1));
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

package com.agentstudio.adapter.ssh;

import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.agentstudio.tool.AgentTool;
import com.agentstudio.tool.ToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class VerifyRemoteDeploymentBackupRestoreTool implements AgentTool {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(120);
    private static final int MAX_OUTPUT_BYTES = 8_000;
    private static final Pattern BACKUP_ID = Pattern.compile("[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}");
    private static final Pattern DRILL_ID = Pattern.compile("restore-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "verify_remote_deployment_backup_restore", "演练受控远程备份恢复",
            "审批后选择固定备份根内最新的合格备份，在隔离的新目录中校验并展开恢复材料；不导入数据库、不修改生产目录、不启动容器，也不接受模型参数。",
            "SSH", "WRITE", "HIGH", 135,
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false));

    private final RemoteDeploymentWorkspace workspace;
    private final RemoteDeploymentService profiles;
    private final ObjectMapper objectMapper;
    private final RemoteDeploymentRestoreDrillCommands commands;
    private final Duration commandTimeout;

    @Autowired
    public VerifyRemoteDeploymentBackupRestoreTool(RemoteDeploymentWorkspace workspace,
                                                   RemoteDeploymentService profiles,
                                                   ObjectMapper objectMapper) {
        this(workspace, profiles, objectMapper, new RemoteDeploymentRestoreDrillCommands(), COMMAND_TIMEOUT);
    }

    VerifyRemoteDeploymentBackupRestoreTool(RemoteDeploymentWorkspace workspace,
                                            RemoteDeploymentService profiles,
                                            ObjectMapper objectMapper,
                                            RemoteDeploymentRestoreDrillCommands commands,
                                            Duration commandTimeout) {
        this.workspace = workspace;
        this.profiles = profiles;
        this.objectMapper = objectMapper;
        this.commands = commands;
        this.commandTimeout = commandTimeout;
    }

    @Override public ToolDescriptor descriptor() { return DESCRIPTOR; }
    @Override public String targetEnvironment() {
        return "SSH:" + profiles.approvalTarget() + "|RESTORE_DRILL:LATEST_ISOLATED";
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        if (!arguments.isObject() || !arguments.isEmpty()) {
            throw new IllegalArgumentException("恢复演练不接受备份 ID、路径、命令、参数、环境变量、生产恢复或删除选项");
        }
        var profile = profiles.current();
        return workspace.execute(profile, (session, access, properties) -> {
            var output = new BoundedSshOutputStream(MAX_OUTPUT_BYTES);
            var started = System.nanoTime();
            try (var channel = session.createExecChannel(commands.command(profile))) {
                channel.setOut(output);
                channel.setRedirectErrorStream(true);
                channel.open().verify(properties.connectTimeout());
                var deadline = System.nanoTime() + commandTimeout.toNanos();
                while (true) {
                    if (Thread.currentThread().isInterrupted()) {
                        channel.close(true);
                        throw new InterruptedException("远程恢复演练已中断，已关闭远程命令通道");
                    }
                    var remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        channel.close(true);
                        throw new IllegalStateException("远程恢复演练超过 " + commandTimeout.toSeconds()
                                + " 秒，已关闭远程命令通道");
                    }
                    var waitMillis = Math.min(250, Math.max(1, Duration.ofNanos(remaining).toMillis()));
                    if (channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), waitMillis)
                            .contains(ClientChannelEvent.CLOSED)) break;
                }
                var exitCode = channel.getExitStatus();
                if (exitCode == null) throw new IllegalStateException("远程恢复演练通道关闭但未返回退出码");
                var durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                if (exitCode != 0) {
                    return result(profile, false, exitCode, durationMs, output.value(), output.truncated(), Map.of());
                }
                if (output.truncated()) {
                    throw new IllegalStateException("远程恢复演练成功回执超过输出上限，无法安全校验结果");
                }
                var fields = parse(output.value());
                validate(fields, profile);
                return result(profile, true, 0, durationMs,
                        "最新备份已在隔离目录完成材料化与一致性校验", false, fields);
            }
        });
    }

    private String result(RemoteDeploymentProfile profile, boolean successful, int exitCode, long durationMs,
                          String output, boolean truncated, Map<String, String> fields) throws Exception {
        var response = new LinkedHashMap<String, Object>();
        response.put("target", profiles.target(profile));
        response.put("backupRoot", profile.remoteBackupRoot());
        response.put("backupId", fields.get("BACKUP_ID"));
        response.put("backupPath", fields.get("BACKUP_PATH"));
        response.put("drillId", fields.get("DRILL_ID"));
        response.put("drillPath", fields.get("DRILL_PATH"));
        response.put("successful", successful);
        response.put("exitCode", exitCode);
        response.put("durationMs", durationMs);
        response.put("databaseBytes", number(fields.get("DATABASE_BYTES")));
        response.put("restoredUploadsBytes", number(fields.get("RESTORED_UPLOADS_BYTES")));
        response.put("restoredFileCount", number(fields.get("RESTORED_FILE_COUNT")));
        response.put("manifestSha256", fields.get("MANIFEST_SHA256"));
        response.put("drillSha256", fields.get("DRILL_SHA256"));
        response.put("productionModified", false);
        response.put("databaseImported", false);
        response.put("output", output);
        response.put("outputTruncated", truncated);
        return objectMapper.writeValueAsString(response);
    }

    private Map<String, String> parse(String output) {
        var fields = new LinkedHashMap<String, String>();
        for (var line : output.lines().toList()) {
            var separator = line.indexOf('=');
            if (separator > 0) fields.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return fields;
    }

    private void validate(Map<String, String> fields, RemoteDeploymentProfile profile) {
        var backupId = fields.getOrDefault("BACKUP_ID", "");
        var drillId = fields.getOrDefault("DRILL_ID", "");
        var backupRoot = profile.remoteBackupRoot().replaceAll("/+$", "");
        if (!BACKUP_ID.matcher(backupId).matches()
                || !DRILL_ID.matcher(drillId).matches()
                || !fields.getOrDefault("BACKUP_PATH", "").equals(backupRoot + "/" + backupId)
                || !fields.getOrDefault("DRILL_PATH", "").equals(backupRoot + "/restore-drills/" + drillId)
                || !SHA256.matcher(fields.getOrDefault("MANIFEST_SHA256", "")).matches()
                || !SHA256.matcher(fields.getOrDefault("DRILL_SHA256", "")).matches()
                || number(fields.get("DATABASE_BYTES")) <= 0
                || number(fields.get("RESTORED_FILE_COUNT")) < 7) {
            throw new IllegalStateException("远程恢复演练结果不完整或隔离路径校验失败");
        }
    }

    private long number(String value) {
        if (value == null) return 0;
        try { return Long.parseLong(value); }
        catch (NumberFormatException ignored) { return 0; }
    }
}

package com.agentstudio.adapter.ssh;

import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

import com.agentstudio.tool.AgentTool;
import com.agentstudio.tool.ToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class InspectRemoteDeploymentTool implements AgentTool {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_OUTPUT_BYTES = 16_000;
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "inspect_remote_deployment", "检查受控远程部署",
            "审批后对固定生产部署目标执行 Compose、Nginx、站点健康或发布指纹的只读诊断；不接受路径、命令、服务名、URL、参数或环境变量。",
            "SSH", "EXECUTE", "HIGH", 45,
            Map.of("type", "object", "properties", Map.of(
                    "task", Map.of("type", "string", "enum", RemoteDeploymentCommands.TASKS,
                            "description", "平台固定只读部署诊断")),
                    "required", java.util.List.of("task"), "additionalProperties", false));

    private final RemoteDeploymentWorkspace workspace;
    private final RemoteDeploymentService profiles;
    private final ObjectMapper objectMapper;
    private final RemoteDeploymentCommands commands;
    private final Duration commandTimeout;

    @Autowired
    public InspectRemoteDeploymentTool(RemoteDeploymentWorkspace workspace, RemoteDeploymentService profiles,
                                       ObjectMapper objectMapper) {
        this(workspace, profiles, objectMapper, new RemoteDeploymentCommands(), COMMAND_TIMEOUT);
    }

    InspectRemoteDeploymentTool(RemoteDeploymentWorkspace workspace, RemoteDeploymentService profiles,
                                ObjectMapper objectMapper, RemoteDeploymentCommands commands,
                                Duration commandTimeout) {
        this.workspace = workspace; this.profiles = profiles; this.objectMapper = objectMapper;
        this.commands = commands; this.commandTimeout = commandTimeout;
    }

    @Override public ToolDescriptor descriptor() { return DESCRIPTOR; }
    @Override public String targetEnvironment() { return "SSH:" + profiles.approvalTarget(); }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        if (!arguments.isObject() || arguments.size() != 1 || !arguments.has("task")) {
            throw new IllegalArgumentException("部署诊断只接受固定 task，不接受路径、命令、参数、服务名、URL 或环境变量");
        }
        var task = arguments.path("task").asText("").trim();
        if (!RemoteDeploymentCommands.TASKS.contains(task)) {
            throw new IllegalArgumentException("task 只允许五种固定部署诊断");
        }
        var profile = profiles.current();
        return workspace.execute(profile, (session, access, properties) -> {
            var output = new BoundedSshOutputStream(MAX_OUTPUT_BYTES);
            var started = System.nanoTime();
            try (var channel = session.createExecChannel(commands.command(task, profile))) {
                channel.setOut(output); channel.setRedirectErrorStream(true);
                channel.open().verify(properties.connectTimeout());
                var deadline = System.nanoTime() + commandTimeout.toNanos();
                while (true) {
                    if (Thread.currentThread().isInterrupted()) {
                        channel.close(true); throw new InterruptedException("部署诊断已中断");
                    }
                    var remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        channel.close(true);
                        throw new IllegalStateException("部署诊断超过 " + commandTimeout.toSeconds() + " 秒，已关闭远程命令通道");
                    }
                    var waitMillis = Math.min(250, Math.max(1, Duration.ofNanos(remaining).toMillis()));
                    if (channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), waitMillis)
                            .contains(ClientChannelEvent.CLOSED)) break;
                }
                var exitCode = channel.getExitStatus();
                if (exitCode == null) throw new IllegalStateException("远程命令通道关闭但未返回退出码");
                var response = new LinkedHashMap<String, Object>();
                response.put("task", task); response.put("target", profiles.target(profile));
                response.put("deploymentRoot", profile.remoteDeployRoot());
                response.put("composeProject", profile.composeProject());
                response.put("successful", exitCode == 0); response.put("exitCode", exitCode);
                response.put("durationMs", Duration.ofNanos(System.nanoTime() - started).toMillis());
                response.put("output", output.value()); response.put("outputTruncated", output.truncated());
                return objectMapper.writeValueAsString(response);
            }
        });
    }
}

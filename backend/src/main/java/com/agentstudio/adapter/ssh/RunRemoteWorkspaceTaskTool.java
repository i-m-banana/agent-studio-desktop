package com.agentstudio.adapter.ssh;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentstudio.tool.AgentTool;
import com.agentstudio.tool.ToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RunRemoteWorkspaceTaskTool implements AgentTool {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(75);
    private static final int MAX_OUTPUT_BYTES = 16_000;
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "run_remote_workspace_task", "运行受控 SSH 任务",
            "审批后在已配置 SSH 工作区运行固定的 Git 诊断或 Maven/npm 测试构建任务；不接受命令、参数、环境变量或 Shell 文本。",
            "SSH", "EXECUTE", "HIGH", 90,
            Map.of("type", "object", "properties", Map.of(
                    "path", Map.of("type", "string", "description", "已授权远程根内的相对项目目录"),
                    "task", Map.of("type", "string", "enum", List.of(
                            "GIT_STATUS", "GIT_DIFF_SUMMARY", "MAVEN_TEST", "NPM_TEST", "NPM_BUILD"),
                            "description", "平台固定任务")),
                    "required", List.of("path", "task"), "additionalProperties", false));

    private final RemoteSftpWorkspace workspace;
    private final ObjectMapper objectMapper;
    private final RemoteExecCommands commands;
    private final Duration commandTimeout;

    @Autowired
    public RunRemoteWorkspaceTaskTool(RemoteSftpWorkspace workspace, ObjectMapper objectMapper) {
        this(workspace, objectMapper, new RemoteExecCommands(), COMMAND_TIMEOUT);
    }

    RunRemoteWorkspaceTaskTool(RemoteSftpWorkspace workspace, ObjectMapper objectMapper,
                               RemoteExecCommands commands, Duration commandTimeout) {
        this.workspace = workspace;
        this.objectMapper = objectMapper;
        this.commands = commands;
        this.commandTimeout = commandTimeout;
    }

    @Override public ToolDescriptor descriptor() { return DESCRIPTOR; }
    @Override public String targetEnvironment() { return "SSH:" + workspace.approvalTarget(); }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        var requestedPath = arguments.path("path").asText("").trim();
        if (requestedPath.isBlank()) throw new IllegalArgumentException("path 不能为空");
        var task = arguments.path("task").asText("").trim();
        commands.validateRelativePath(requestedPath);
        var marker = commands.marker(task);
        var directoryMarker = commands.directoryMarker(task);

        return workspace.executeWithSession((session, access, properties) -> {
            var directory = access.requireDirectory(requestedPath);
            access.requireProjectMarker(directory, marker, directoryMarker);
            var command = commands.command(task, directory);
            var output = new BoundedOutputStream(MAX_OUTPUT_BYTES);
            var started = System.nanoTime();
            try (var channel = session.createExecChannel(command)) {
                channel.setOut(output);
                channel.setRedirectErrorStream(true);
                channel.open().verify(properties.connectTimeout());
                var deadline = System.nanoTime() + commandTimeout.toNanos();
                while (true) {
                    if (Thread.currentThread().isInterrupted()) {
                        channel.close(true);
                        throw new InterruptedException("受控 SSH 任务已中断");
                    }
                    var remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        channel.close(true);
                        throw new IllegalStateException(
                                "受控 SSH 任务超过 " + commandTimeout.toSeconds() + " 秒，已关闭远程命令通道");
                    }
                    var waitMillis = Math.min(250, Math.max(1, Duration.ofNanos(remaining).toMillis()));
                    var events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), waitMillis);
                    if (events.contains(ClientChannelEvent.CLOSED)) break;
                }
                var exitCode = channel.getExitStatus();
                if (exitCode == null) throw new IllegalStateException("远程命令通道关闭但未返回退出码");
                var response = new LinkedHashMap<String, Object>();
                response.put("task", task);
                response.put("target", properties.target());
                response.put("path", access.relative(directory));
                response.put("successful", exitCode == 0);
                response.put("exitCode", exitCode);
                response.put("durationMs", Duration.ofNanos(System.nanoTime() - started).toMillis());
                response.put("output", output.value());
                response.put("outputTruncated", output.truncated());
                return objectMapper.writeValueAsString(response);
            }
        });
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final byte[] first;
        private final byte[] tail;
        private int firstSize;
        private int tailSize;
        private int tailCursor;
        private long total;

        private BoundedOutputStream(int limit) {
            this.first = new byte[limit / 2];
            this.tail = new byte[limit / 2];
        }

        @Override public synchronized void write(int value) { append((byte) value); }

        @Override public synchronized void write(byte[] values, int offset, int length) {
            for (int index = 0; index < length; index++) append(values[offset + index]);
        }

        private void append(byte value) {
            total++;
            if (firstSize < first.length) first[firstSize++] = value;
            else {
                tail[tailCursor] = value;
                tailCursor = (tailCursor + 1) % tail.length;
                if (tailSize < tail.length) tailSize++;
            }
        }

        synchronized boolean truncated() { return total > first.length + tail.length; }

        synchronized String value() {
            if (total <= first.length) return new String(first, 0, firstSize, StandardCharsets.UTF_8);
            if (!truncated()) return new String(first, 0, firstSize, StandardCharsets.UTF_8)
                    + new String(tail, 0, tailSize, StandardCharsets.UTF_8);
            var orderedTail = new byte[tailSize];
            for (int index = 0; index < tailSize; index++) {
                orderedTail[index] = tail[(tailCursor + index) % tail.length];
            }
            return new String(first, 0, firstSize, StandardCharsets.UTF_8)
                    + "\n... 输出已截断 ...\n"
                    + new String(orderedTail, StandardCharsets.UTF_8);
        }
    }
}

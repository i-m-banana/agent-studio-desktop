package com.agentstudio.coding;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.agentstudio.tool.AgentTool;
import com.agentstudio.tool.ToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RunWorkspaceVerificationTool implements AgentTool {
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(75);
    private static final int MAX_OUTPUT_CHARS = 16_000;
    private static final Set<String> TASKS = Set.of("MAVEN_TEST", "NPM_TEST", "NPM_BUILD");
    private static final Set<String> ALLOWED_ENVIRONMENT = Set.of(
            "SystemRoot", "WINDIR", "ComSpec", "PATH", "PATHEXT", "TEMP", "TMP",
            "JAVA_HOME", "MAVEN_HOME", "M2_HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA",
            "NPM_CONFIG_CACHE", "HOME", "LANG", "LC_ALL");
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "run_workspace_verification", "运行受审工作区验证",
            "审批后在授权工作区运行固定的 Maven/npm 测试或构建任务。会执行项目代码，但不接受任意命令或额外参数。",
            "BUILTIN", "EXECUTE", "HIGH", 90,
            Map.of("type", "object", "properties", Map.of(
                    "path", Map.of("type", "string", "description", "包含 pom.xml 或 package.json 的相对目录"),
                    "task", Map.of("type", "string", "enum", List.of("MAVEN_TEST", "NPM_TEST", "NPM_BUILD"),
                            "description", "固定验证任务")),
                    "required", List.of("path", "task"), "additionalProperties", false));

    private final CodingWorkspace workspace;
    private final ObjectMapper objectMapper;
    private final WorkspaceVerificationCommands commands;
    private final Duration processTimeout;

    @Autowired
    public RunWorkspaceVerificationTool(CodingWorkspace workspace, ObjectMapper objectMapper,
                                        WorkspaceVerificationCommands commands) {
        this(workspace, objectMapper, commands, PROCESS_TIMEOUT);
    }

    RunWorkspaceVerificationTool(CodingWorkspace workspace, ObjectMapper objectMapper,
                                 WorkspaceVerificationCommands commands, Duration processTimeout) {
        this.workspace = workspace;
        this.objectMapper = objectMapper;
        this.commands = commands;
        this.processTimeout = processTimeout;
    }

    @Override public ToolDescriptor descriptor() { return DESCRIPTOR; }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        var requestedPath = arguments.path("path").asText("").trim();
        if (requestedPath.isBlank()) throw new IllegalArgumentException("path 不能为空");
        var task = arguments.path("task").asText("").trim();
        if (!TASKS.contains(task)) throw new IllegalArgumentException("task 只允许 MAVEN_TEST、NPM_TEST 或 NPM_BUILD");
        var directory = workspace.requireDirectory(requestedPath);
        requireProjectMarker(directory, task);
        var command = commands.resolve(task);
        if (command.executable().startsWith(workspace.root())) {
            throw new IllegalStateException("拒绝执行代码工作区内的 Maven/npm 启动程序");
        }

        var processBuilder = new ProcessBuilder(command.arguments())
                .directory(directory.toFile()).redirectErrorStream(true);
        sanitizeEnvironment(processBuilder.environment());
        var started = System.nanoTime();
        var process = processBuilder.start();
        var output = new BoundedOutput(MAX_OUTPUT_CHARS);
        var readFailure = new AtomicReference<Exception>();
        var reader = Thread.startVirtualThread(() -> {
            try (var stream = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                var buffer = new char[2048];
                int count;
                while ((count = stream.read(buffer)) >= 0) output.append(buffer, count);
            } catch (Exception exception) {
                readFailure.set(exception);
            }
        });
        final boolean completed;
        try {
            completed = process.waitFor(processTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                terminateProcessTree(process);
                throw new IllegalStateException("工作区验证超过 " + processTimeout.toSeconds() + " 秒，已终止进程树");
            }
        } catch (InterruptedException exception) {
            terminateProcessTree(process);
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            if (process.isAlive()) terminateProcessTree(process);
            reader.join(2_000);
        }
        if (readFailure.get() != null) throw new IllegalStateException("读取验证输出失败", readFailure.get());

        var response = new LinkedHashMap<String, Object>();
        response.put("task", task);
        response.put("path", workspace.relative(directory));
        response.put("successful", process.exitValue() == 0);
        response.put("exitCode", process.exitValue());
        response.put("durationMs", Duration.ofNanos(System.nanoTime() - started).toMillis());
        response.put("output", output.value());
        response.put("outputTruncated", output.truncated());
        return objectMapper.writeValueAsString(response);
    }

    private void requireProjectMarker(java.nio.file.Path directory, String task) throws Exception {
        var markerName = "MAVEN_TEST".equals(task) ? "pom.xml" : "package.json";
        var marker = directory.resolve(markerName);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || !workspace.isSafeEntry(marker)) {
            throw new IllegalArgumentException("目录中缺少安全的 " + markerName);
        }
        workspace.requireRegularFile(workspace.relative(marker));
    }

    private void sanitizeEnvironment(Map<String, String> environment) {
        var original = Map.copyOf(environment);
        environment.clear();
        var windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows");
        original.forEach((name, value) -> {
            var allowed = windows
                    ? ALLOWED_ENVIRONMENT.stream().anyMatch(item -> item.equalsIgnoreCase(name))
                    : ALLOWED_ENVIRONMENT.contains(name);
            if (allowed) environment.put(name, value);
        });
        environment.put("CI", "true");
        environment.put("NO_COLOR", "1");
    }

    private void terminateProcessTree(Process process) {
        process.toHandle().descendants().forEach(handle -> {
            if (handle.isAlive()) handle.destroyForcibly();
        });
        if (process.isAlive()) process.destroyForcibly();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class BoundedOutput {
        private final int limit;
        private final StringBuilder captured = new StringBuilder();
        private boolean truncated;

        private BoundedOutput(int limit) {
            this.limit = limit;
        }

        synchronized void append(char[] buffer, int count) {
            var value = new String(buffer, 0, count);
            if (!truncated) {
                captured.append(value);
                if (captured.length() <= limit) return;
                var combined = captured.toString();
                captured.setLength(0);
                captured.append(combined, 0, limit / 2);
                captured.append(combined, combined.length() - limit / 2, combined.length());
                truncated = true;
                return;
            }
            var tail = captured.substring(limit / 2) + value;
            captured.setLength(limit / 2);
            captured.append(tail, Math.max(0, tail.length() - limit / 2), tail.length());
        }

        synchronized String value() {
            if (!truncated) return captured.toString();
            return captured.substring(0, limit / 2) + "\n... 输出已截断 ...\n"
                    + captured.substring(limit / 2);
        }

        synchronized boolean truncated() {
            return truncated;
        }
    }
}

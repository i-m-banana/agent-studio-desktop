package com.agentstudio.adapter.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** MCP stdio transport. Every operation owns a short-lived child process for isolation and reliable cleanup. */
@Component
public class StdioMcpClient implements McpTransportClient {
    private final ObjectMapper objectMapper;

    public StdioMcpClient(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    @Override
    public String transport() { return "STDIO"; }

    @Override
    public McpDiscovery discover(McpServer server) throws Exception {
        try (var session = start(server)) {
            var initialized = initialize(session);
            var tools = new ArrayList<McpRemoteTool>();
            String cursor = null;
            do {
                var params = cursor == null ? Map.<String, Object>of() : Map.<String, Object>of("cursor", cursor);
                var response = session.request("tools/list", params, Duration.ofSeconds(20));
                for (var node : response.path("result").path("tools")) {
                    var schemaNode = node.path("inputSchema");
                    if (!schemaNode.isObject()) throw new IOException("MCP 工具缺少 object 类型 inputSchema");
                    var schema = objectMapper.convertValue(schemaNode, new TypeReference<Map<String, Object>>() {});
                    tools.add(new McpRemoteTool(requiredText(node, "name"), node.path("title").asText(""),
                            node.path("description").asText(""), schema));
                }
                cursor = response.path("result").path("nextCursor").asText(null);
                if (tools.size() > 200) throw new IOException("MCP Server 暴露工具超过 200 个，已停止同步");
            } while (cursor != null && !cursor.isBlank());
            return new McpDiscovery(initialized.protocolVersion(), initialized.serverName(),
                    initialized.serverVersion(), List.copyOf(tools));
        }
    }

    @Override
    public String call(McpServer server, McpCatalogTool tool, JsonNode arguments) throws Exception {
        try (var session = start(server)) {
            initialize(session);
            var response = session.request("tools/call", Map.of("name", tool.remoteName(), "arguments", arguments),
                    Duration.ofSeconds(tool.timeoutSeconds()));
            return resultText(response.path("result"));
        }
    }

    private Initialized initialize(Session session) throws Exception {
        var response = session.request("initialize", Map.of(
                "protocolVersion", StreamableHttpMcpClient.REQUESTED_PROTOCOL,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "agent-studio-desktop", "version", "0.1.0")),
                Duration.ofSeconds(15));
        var result = response.path("result");
        var protocol = requiredText(result, "protocolVersion");
        if (!StreamableHttpMcpClient.REQUESTED_PROTOCOL.equals(protocol)) {
            throw new IOException("MCP 协议版本不兼容：Server 返回 " + protocol + "，客户端支持 "
                    + StreamableHttpMcpClient.REQUESTED_PROTOCOL);
        }
        if (!result.path("capabilities").has("tools")) throw new IOException("MCP Server 未声明 tools 能力");
        session.notification("notifications/initialized", Map.of());
        return new Initialized(protocol, result.path("serverInfo").path("name").asText("unknown"),
                result.path("serverInfo").path("version").asText("unknown"));
    }

    private Session start(McpServer server) throws IOException {
        var command = new ArrayList<String>();
        command.add(server.command());
        command.addAll(server.arguments());
        var builder = new ProcessBuilder(command);
        if (server.workingDirectory() != null) builder.directory(Path.of(server.workingDirectory()).toFile());
        var childEnvironment = builder.environment();
        childEnvironment.clear();
        for (var name : List.of("PATH", "Path", "PATHEXT", "SystemRoot", "WINDIR", "TEMP", "TMP", "USERPROFILE", "HOME")) {
            var value = System.getenv(name);
            if (value != null && !value.isBlank()) childEnvironment.put(name, value);
        }
        for (var binding : server.environment().entrySet()) {
            var value = System.getenv(binding.getValue());
            if (value == null || value.isBlank()) {
                throw new IOException("宿主环境变量 " + binding.getValue() + " 未设置，无法注入 " + binding.getKey());
            }
            childEnvironment.put(binding.getKey(), value);
        }
        return new Session(builder.start());
    }

    private String resultText(JsonNode result) throws IOException {
        var output = new StringBuilder();
        for (var item : result.path("content")) {
            if ("text".equals(item.path("type").asText()) && item.has("text")) {
                if (!output.isEmpty()) output.append('\n');
                output.append(item.path("text").asText());
            } else {
                if (!output.isEmpty()) output.append('\n');
                output.append(objectMapper.writeValueAsString(item));
            }
        }
        if (result.has("structuredContent")) {
            if (!output.isEmpty()) output.append('\n');
            output.append(objectMapper.writeValueAsString(result.path("structuredContent")));
        }
        var text = output.isEmpty() ? "MCP 工具执行完成（无文本结果）" : output.toString();
        if (result.path("isError").asBoolean(false)) throw new IOException("MCP 工具返回错误：" + abbreviate(text, 1000));
        return abbreviate(text, 32000);
    }

    private String requiredText(JsonNode node, String field) throws IOException {
        var value = node.path(field).asText("");
        if (value.isBlank()) throw new IOException("MCP 响应缺少 " + field);
        return value;
    }

    private String abbreviate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private final class Session implements AutoCloseable {
        private final Process process;
        private final BufferedReader stdout;
        private final BufferedWriter stdin;
        private final BufferedReader stderr;
        private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final StringBuilder stderrTail = new StringBuilder();

        private Session(Process process) {
            this.process = process;
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            stderr = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
            executor.submit(this::captureStderr);
        }

        private JsonNode request(String method, Object params, Duration timeout) throws Exception {
            var id = UUID.randomUUID().toString();
            var body = new LinkedHashMap<String, Object>();
            body.put("jsonrpc", "2.0"); body.put("id", id); body.put("method", method); body.put("params", params);
            send(body);
            var deadline = System.nanoTime() + timeout.toNanos();
            try {
                while (true) {
                    var remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new TimeoutException();
                    String line;
                    try {
                        line = executor.submit(stdout::readLine).get(remaining, TimeUnit.NANOSECONDS);
                    } catch (ExecutionException exception) {
                        throw exception.getCause() instanceof Exception cause ? cause : exception;
                    }
                    if (line == null) throw new IOException("MCP stdio 进程提前退出" + diagnostic());
                    JsonNode message;
                    try { message = objectMapper.readTree(line); }
                    catch (Exception exception) { throw new IOException("MCP stdio stdout 含有非 JSON-RPC 内容" + diagnostic()); }
                    if (message.has("method") && message.has("id")) {
                        send(Map.of("jsonrpc", "2.0", "id", message.get("id"), "error",
                                Map.of("code", -32601, "message", "client request unsupported")));
                        continue;
                    }
                    if (!id.equals(message.path("id").asText())) continue;
                    if (message.has("error")) throw new IOException("MCP " + method + " 失败："
                            + message.path("error").path("message").asText("未知协议错误") + diagnostic());
                    return message;
                }
            } catch (TimeoutException exception) {
                notification("notifications/cancelled", Map.of("requestId", id, "reason", "client timeout"));
                throw new SocketTimeoutException("MCP " + method + " 超时" + diagnostic());
            }
        }

        private void notification(String method, Object params) throws IOException {
            send(Map.of("jsonrpc", "2.0", "method", method, "params", params));
        }

        private synchronized void send(Object body) throws IOException {
            stdin.write(objectMapper.writeValueAsString(body));
            stdin.newLine();
            stdin.flush();
        }

        private void captureStderr() {
            try {
                String line;
                while ((line = stderr.readLine()) != null) {
                    synchronized (stderrTail) {
                        stderrTail.append(line).append('\n');
                        if (stderrTail.length() > 4000) stderrTail.delete(0, stderrTail.length() - 4000);
                    }
                }
            } catch (IOException ignored) { }
        }

        private String diagnostic() {
            synchronized (stderrTail) {
                var value = stderrTail.toString().trim();
                return value.isBlank() ? "" : "；stderr：" + abbreviate(value, 1000);
            }
        }

        @Override
        public void close() {
            try { stdin.close(); } catch (Exception ignored) { }
            try {
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.descendants().forEach(handle -> handle.destroy());
                    process.destroy();
                    if (!process.waitFor(1, TimeUnit.SECONDS)) {
                        process.descendants().forEach(handle -> handle.destroyForcibly());
                        process.destroyForcibly();
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            executor.shutdownNow();
            try { stdout.close(); stderr.close(); } catch (Exception ignored) { }
        }
    }

    private record Initialized(String protocolVersion, String serverName, String serverVersion) {}
}

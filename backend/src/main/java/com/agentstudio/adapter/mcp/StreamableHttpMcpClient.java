package com.agentstudio.adapter.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class StreamableHttpMcpClient implements McpTransportClient {
    static final String REQUESTED_PROTOCOL = "2025-06-18";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public StreamableHttpMcpClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String transport() { return "STREAMABLE_HTTP"; }

    @Override
    public McpDiscovery discover(McpServer server) throws Exception {
        var session = initialize(server);
        try {
            var tools = new ArrayList<McpRemoteTool>();
            String cursor = null;
            if (session.tools()) do {
                var params = cursor == null ? Map.<String, Object>of() : Map.<String, Object>of("cursor", cursor);
                var response = request(server, session, "tools/list", params, Duration.ofSeconds(20));
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
            var resources = new ArrayList<McpRemoteResource>();
            cursor = null;
            if (session.resources()) do {
                var params = cursor == null ? Map.<String, Object>of() : Map.<String, Object>of("cursor", cursor);
                var response = request(server, session, "resources/list", params, Duration.ofSeconds(20));
                for (var node : response.path("result").path("resources")) {
                    resources.add(new McpRemoteResource(requiredText(node, "uri"), requiredText(node, "name"),
                            node.path("title").asText(""), node.path("description").asText(""),
                            node.path("mimeType").asText(null), node.has("size") ? node.path("size").asLong() : null));
                }
                cursor = response.path("result").path("nextCursor").asText(null);
                if (resources.size() > 500) throw new IOException("MCP Server 暴露资源超过 500 个，已停止同步");
            } while (cursor != null && !cursor.isBlank());
            var prompts = new ArrayList<McpRemotePrompt>();
            cursor = null;
            if (session.prompts()) do {
                var params = cursor == null ? Map.<String, Object>of() : Map.<String, Object>of("cursor", cursor);
                var response = request(server, session, "prompts/list", params, Duration.ofSeconds(20));
                for (var node : response.path("result").path("prompts")) prompts.add(prompt(node));
                cursor = response.path("result").path("nextCursor").asText(null);
                if (prompts.size() > 200) throw new IOException("MCP Server 暴露提示词超过 200 个，已停止同步");
            } while (cursor != null && !cursor.isBlank());
            return new McpDiscovery(session.protocolVersion(), session.serverName(), session.serverVersion(),
                    List.copyOf(tools), List.copyOf(resources), List.copyOf(prompts));
        } finally {
            close(server, session);
        }
    }

    @Override
    public List<McpResourceContent> readResource(McpServer server, String uri) throws Exception {
        var session = initialize(server);
        try {
            if (!session.resources()) throw new IOException("MCP Server 未声明 resources 能力");
            var response = request(server, session, "resources/read", Map.of("uri", uri), Duration.ofSeconds(30));
            var contents = new ArrayList<McpResourceContent>();
            for (var node : response.path("result").path("contents")) {
                contents.add(new McpResourceContent(requiredText(node, "uri"), node.path("mimeType").asText(null),
                        node.has("text") ? node.path("text").asText() : null,
                        node.has("blob") ? node.path("blob").asText() : null));
            }
            return List.copyOf(contents);
        } finally { close(server, session); }
    }

    @Override
    public McpPromptResult getPrompt(McpServer server, String name, Map<String, String> arguments) throws Exception {
        var session = initialize(server);
        try {
            if (!session.prompts()) throw new IOException("MCP Server 未声明 prompts 能力");
            var response = request(server, session, "prompts/get", Map.of("name", name, "arguments", arguments),
                    Duration.ofSeconds(30));
            var result = response.path("result");
            var messages = new ArrayList<McpPromptMessage>();
            for (var node : result.path("messages")) {
                messages.add(new McpPromptMessage(requiredText(node, "role"), node.path("content")));
            }
            return new McpPromptResult(result.path("description").asText(""), List.copyOf(messages));
        } finally { close(server, session); }
    }

    @Override
    public String call(McpServer server, McpCatalogTool tool, JsonNode arguments) throws Exception {
        var session = initialize(server);
        try {
            var response = request(server, session, "tools/call",
                    Map.of("name", tool.remoteName(), "arguments", arguments),
                    Duration.ofSeconds(tool.timeoutSeconds()));
            var result = response.path("result");
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
        } finally {
            close(server, session);
        }
    }

    private Session initialize(McpServer server) throws Exception {
        var id = UUID.randomUUID().toString();
        var response = post(server, null, null, jsonRpc(id, "initialize", Map.of(
                "protocolVersion", REQUESTED_PROTOCOL,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "agent-studio-desktop", "version", "0.1.0"))),
                Duration.ofSeconds(15));
        var body = responseNode(response);
        if (body.has("error")) {
            throw new IOException("MCP initialize 失败：" + body.path("error").path("message").asText("未知协议错误"));
        }
        if (!id.equals(body.path("id").asText())) throw new IOException("MCP initialize 响应 id 与请求不一致");
        var result = body.path("result");
        var protocol = requiredText(result, "protocolVersion");
        if (!REQUESTED_PROTOCOL.equals(protocol)) {
            throw new IOException("MCP 协议版本不兼容：Server 返回 " + protocol + "，客户端支持 " + REQUESTED_PROTOCOL);
        }
        var capabilities = result.path("capabilities");
        if (!capabilities.has("tools") && !capabilities.has("resources") && !capabilities.has("prompts")) {
            throw new IOException("MCP Server 未声明 tools、resources 或 prompts 能力");
        }
        var session = new Session(response.headers().firstValue("Mcp-Session-Id").orElse(null), protocol,
                result.path("serverInfo").path("name").asText("unknown"),
                result.path("serverInfo").path("version").asText("unknown"), capabilities.has("tools"),
                capabilities.has("resources"), capabilities.has("prompts"));
        post(server, session, null, notification("notifications/initialized"), Duration.ofSeconds(10));
        return session;
    }

    private JsonNode request(McpServer server, Session session, String method, Object params, Duration timeout) throws Exception {
        var id = UUID.randomUUID().toString();
        var response = post(server, session, method, jsonRpc(id, method, params), timeout);
        var body = responseNode(response);
        if (body.has("error")) {
            throw new IOException("MCP " + method + " 失败：" + body.path("error").path("message").asText("未知协议错误"));
        }
        if (!id.equals(body.path("id").asText())) throw new IOException("MCP 响应 id 与请求不一致");
        return body;
    }

    private HttpResponse<String> post(McpServer server, Session session, String method,
                                      Map<String, Object> body, Duration timeout) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(server.endpointUrl()))
                .timeout(timeout).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (method != null) builder.header("Mcp-Method", method);
        if (session != null) {
            builder.header("MCP-Protocol-Version", session.protocolVersion());
            if (session.id() != null) builder.header("Mcp-Session-Id", session.id());
        }
        authorize(builder, server);
        var response = httpClient.send(builder.POST(HttpRequest.BodyPublishers.ofString(
                objectMapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("MCP Server 返回 HTTP " + response.statusCode() + "：" + abbreviate(response.body(), 500));
        }
        return response;
    }

    private JsonNode responseNode(HttpResponse<String> response) throws IOException {
        var contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
        if (contentType.contains("text/event-stream")) {
            JsonNode lastResponse = null;
            for (var line : response.body().replace("\r\n", "\n").split("\n")) {
                if (!line.startsWith("data:")) continue;
                var data = line.substring(5).trim();
                if (!data.isBlank()) {
                    var node = objectMapper.readTree(data);
                    if (node.has("id")) lastResponse = node;
                }
            }
            if (lastResponse == null) throw new IOException("MCP SSE 响应中没有 JSON-RPC response");
            return lastResponse;
        }
        if (response.body() == null || response.body().isBlank()) return objectMapper.createObjectNode();
        return objectMapper.readTree(response.body());
    }

    private void close(McpServer server, Session session) {
        if (session.id() == null) return;
        try {
            var builder = HttpRequest.newBuilder(URI.create(server.endpointUrl())).timeout(Duration.ofSeconds(3))
                    .header("MCP-Protocol-Version", session.protocolVersion()).header("Mcp-Session-Id", session.id());
            authorize(builder, server);
            httpClient.send(builder.DELETE().build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // Session cleanup is best effort; the remote server may respond 405.
        }
    }

    private void authorize(HttpRequest.Builder builder, McpServer server) {
        if (server.apiKeyEnv() == null || server.apiKeyEnv().isBlank()) return;
        var value = System.getenv(server.apiKeyEnv());
        if (value == null || value.isBlank()) throw new IllegalStateException("环境变量 " + server.apiKeyEnv() + " 未设置");
        builder.header("Authorization", "Bearer " + value);
    }

    private Map<String, Object> jsonRpc(String id, String method, Object params) {
        var body = new LinkedHashMap<String, Object>();
        body.put("jsonrpc", "2.0"); body.put("id", id); body.put("method", method); body.put("params", params);
        return body;
    }

    private Map<String, Object> notification(String method) {
        return Map.of("jsonrpc", "2.0", "method", method);
    }

    private String requiredText(JsonNode node, String field) throws IOException {
        var value = node.path(field).asText("");
        if (value.isBlank()) throw new IOException("MCP 响应缺少 " + field);
        return value;
    }

    private McpRemotePrompt prompt(JsonNode node) throws IOException {
        var arguments = new ArrayList<McpPromptArgument>();
        for (var argument : node.path("arguments")) {
            arguments.add(new McpPromptArgument(requiredText(argument, "name"),
                    argument.path("description").asText(""), argument.path("required").asBoolean(false)));
        }
        return new McpRemotePrompt(requiredText(node, "name"), node.path("title").asText(""),
                node.path("description").asText(""), List.copyOf(arguments));
    }

    private String abbreviate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private record Session(String id, String protocolVersion, String serverName, String serverVersion,
                           boolean tools, boolean resources, boolean prompts) {}
}

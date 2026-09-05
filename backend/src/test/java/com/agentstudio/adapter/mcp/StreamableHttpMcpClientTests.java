package com.agentstudio.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StreamableHttpMcpClientTests {
    private HttpServer httpServer;
    private StreamableHttpMcpClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger initialized = new AtomicInteger();

    @BeforeEach
    void startServer() throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/mcp", this::handle);
        httpServer.start();
        client = new StreamableHttpMcpClient(HttpClient.newHttpClient(), objectMapper);
    }

    @AfterEach
    void stopServer() { httpServer.stop(0); }

    @Test
    void negotiatesSessionDiscoversAllPrimitivesAndCallsThem() throws Exception {
        var server = server();
        var discovery = client.discover(server);
        assertThat(discovery.protocolVersion()).isEqualTo("2025-06-18");
        assertThat(discovery.serverName()).isEqualTo("fixture-server");
        assertThat(discovery.tools()).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("echo");
            assertThat(tool.inputSchema()).containsKey("properties");
        });
        assertThat(discovery.resources()).singleElement().extracting(McpRemoteResource::uri)
                .isEqualTo("project://fixture/readme");
        assertThat(discovery.prompts()).singleElement().extracting(McpRemotePrompt::name)
                .isEqualTo("summarize_project");

        assertThat(client.readResource(server, "project://fixture/readme"))
                .singleElement().extracting(McpResourceContent::text).isEqualTo("fixture resource");
        assertThat(client.getPrompt(server, "summarize_project", Map.of("topic", "MCP")))
                .satisfies(result -> assertThat(result.messages()).singleElement()
                        .satisfies(message -> assertThat(message.content().path("text").asText()).isEqualTo("summarize MCP")));

        var catalog = new McpCatalogTool("mcp_fixture_echo", server.id(), "echo", "Echo", "Echo text",
                discovery.tools().getFirst().inputSchema(), "EXECUTE", "HIGH", 5, true, "hash", Instant.now());
        assertThat(client.call(server, catalog, objectMapper.readTree("{\"text\":\"hello\"}")))
                .isEqualTo("echo: hello");
        assertThat(initialized.get()).isEqualTo(4);
    }

    @Test
    void propagatesJsonRpcToolError() throws Exception {
        var server = server();
        var tool = new McpCatalogTool("mcp_fixture_missing", server.id(), "missing", "Missing", "",
                Map.of("type", "object"), "EXECUTE", "HIGH", 5, true, "hash", Instant.now());
        assertThatThrownBy(() -> client.call(server, tool, objectMapper.createObjectNode()))
                .hasMessageContaining("MCP tools/call 失败").hasMessageContaining("unknown tool");
    }

    private void handle(HttpExchange exchange) {
        try {
            if ("DELETE".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(204, -1); return; }
            var body = objectMapper.readTree(exchange.getRequestBody());
            var method = body.path("method").asText();
            if ("initialize".equals(method)) {
                json(exchange, 200, Map.of("jsonrpc", "2.0", "id", body.path("id").asText(), "result", Map.of(
                        "protocolVersion", "2025-06-18", "capabilities", Map.of(
                                "tools", Map.of(), "resources", Map.of(), "prompts", Map.of()),
                        "serverInfo", Map.of("name", "fixture-server", "version", "1.0"))), true);
            } else if ("notifications/initialized".equals(method)) {
                assertThat(exchange.getRequestHeaders().getFirst("Mcp-Session-Id")).isEqualTo("fixture-session");
                assertThat(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version")).isEqualTo("2025-06-18");
                initialized.incrementAndGet(); exchange.sendResponseHeaders(202, -1);
            } else if ("tools/list".equals(method)) {
                var schema = Map.of("type", "object", "properties",
                        Map.of("text", Map.of("type", "string")), "required", List.of("text"));
                var tool = Map.of("name", "echo", "title", "Echo", "description", "Echo text",
                        "inputSchema", schema);
                json(exchange, 200, Map.of("jsonrpc", "2.0", "id", body.path("id").asText(),
                        "result", Map.of("tools", List.of(tool))), false);
            } else if ("resources/list".equals(method)) {
                json(exchange, 200, Map.of("jsonrpc", "2.0", "id", body.path("id").asText(),
                        "result", Map.of("resources", List.of(Map.of("uri", "project://fixture/readme",
                                "name", "Fixture README", "mimeType", "text/plain")))), false);
            } else if ("resources/read".equals(method)) {
                json(exchange, 200, Map.of("jsonrpc", "2.0", "id", body.path("id").asText(),
                        "result", Map.of("contents", List.of(Map.of("uri", "project://fixture/readme",
                                "mimeType", "text/plain", "text", "fixture resource")))), false);
            } else if ("prompts/list".equals(method)) {
                json(exchange, 200, Map.of("jsonrpc", "2.0", "id", body.path("id").asText(),
                        "result", Map.of("prompts", List.of(Map.of("name", "summarize_project",
                                "description", "Summarize a topic", "arguments", List.of(Map.of(
                                        "name", "topic", "description", "Topic", "required", true)))))), false);
            } else if ("prompts/get".equals(method)) {
                var text = "summarize " + body.path("params").path("arguments").path("topic").asText();
                json(exchange, 200, Map.of("jsonrpc", "2.0", "id", body.path("id").asText(),
                        "result", Map.of("description", "Summary prompt", "messages", List.of(Map.of(
                                "role", "user", "content", Map.of("type", "text", "text", text))))), false);
            } else if ("tools/call".equals(method) && "echo".equals(body.path("params").path("name").asText())) {
                json(exchange, 200, Map.of("jsonrpc", "2.0", "id", body.path("id").asText(), "result", Map.of(
                        "content", List.of(Map.of("type", "text", "text", "echo: "
                                + body.path("params").path("arguments").path("text").asText())), "isError", false)), false);
            } else {
                json(exchange, 200, Map.of("jsonrpc", "2.0", "id", body.path("id").asText(), "error",
                        Map.of("code", -32601, "message", "unknown tool")), false);
            }
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        } finally {
            exchange.close();
        }
    }

    private void json(HttpExchange exchange, int status, Object body, boolean session) throws Exception {
        var bytes = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (session) exchange.getResponseHeaders().set("Mcp-Session-Id", "fixture-session");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private McpServer server() {
        var now = Instant.now();
        return new McpServer("server-1", "fixture", "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/mcp",
                null, true, "NOT_SYNCED", null, null, null, null, null, now, now, List.of());
    }
}

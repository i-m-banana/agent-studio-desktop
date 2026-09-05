package com.agentstudio.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class StdioMcpClientTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StdioMcpClient client = new StdioMcpClient(objectMapper);

    @Test
    void discoversAndCallsToolOverNewlineDelimitedStdio() throws Exception {
        assumeTrue(nodeAvailable());
        var script = Path.of("..", "examples", "mcp-fixture-server", "stdio-server.mjs").toAbsolutePath().normalize();
        var now = Instant.now();
        var server = new McpServer("stdio-1", "stdio fixture", "STDIO", null, null, "node",
                List.of(script.toString()), null, Map.of(), true, "NOT_SYNCED", null, null, null,
                null, null, now, now, List.of());

        var discovery = client.discover(server);
        assertThat(discovery.protocolVersion()).isEqualTo("2025-06-18");
        assertThat(discovery.serverName()).isEqualTo("agent-studio-stdio-fixture");
        assertThat(discovery.tools()).singleElement().extracting(McpRemoteTool::name)
                .isEqualTo("local_project_status");
        assertThat(discovery.resources()).singleElement().extracting(McpRemoteResource::uri)
                .isEqualTo("project://mcp/acceptance");
        assertThat(discovery.prompts()).singleElement().extracting(McpRemotePrompt::name)
                .isEqualTo("mcp_acceptance");

        assertThat(client.readResource(server, "project://mcp/acceptance"))
                .singleElement().extracting(McpResourceContent::text).asString().contains("集中验收");
        assertThat(client.getPrompt(server, "mcp_acceptance", Map.of("module", "MCP")))
                .extracting(McpPromptResult::description).isEqualTo("MCP 验收提示词");

        var remote = discovery.tools().getFirst();
        var catalog = new McpCatalogTool("mcp_stdio_status", server.id(), remote.name(), remote.title(),
                remote.description(), remote.inputSchema(), "EXECUTE", "HIGH", 5, true, true, "hash", now);
        assertThat(client.call(server, catalog, objectMapper.readTree("{\"module\":\"MCP\"}")))
                .contains("MCP 模块").contains("调用正常");
    }

    private boolean nodeAvailable() {
        try {
            return new ProcessBuilder("node", "--version").start().waitFor() == 0;
        } catch (Exception exception) {
            return false;
        }
    }
}

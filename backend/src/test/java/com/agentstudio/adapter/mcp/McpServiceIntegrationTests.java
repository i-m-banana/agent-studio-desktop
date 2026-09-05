package com.agentstudio.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.agentstudio.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class McpServiceIntegrationTests {
    @Autowired McpService service;
    @Autowired ToolRegistry tools;
    @MockitoBean McpTransportClient client;

    @Test
    void syncAddsNamespacedHighRiskToolToUnifiedRegistry() throws Exception {
        when(client.discover(any())).thenReturn(new McpDiscovery("2025-06-18", "test-server", "1.0", List.of(
                new McpRemoteTool("lookup.issue", "Lookup issue", "Read issue by id",
                        Map.of("type", "object", "properties", Map.of("id", Map.of("type", "string")),
                                "required", List.of("id"), "additionalProperties", false)))));
        when(client.call(any(), any(), any())).thenReturn("issue result");
        var server = service.create(new McpServerRequest("mcp-" + UUID.randomUUID(), "http://localhost:39999/mcp", null));
        var result = service.sync(server.id());
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.toolCount()).isEqualTo(1);
        var descriptor = tools.descriptors().stream().filter(tool -> "MCP".equals(tool.source())).findFirst().orElseThrow();
        assertThat(descriptor).satisfies(tool -> {
            assertThat(tool.name()).startsWith("mcp_");
            assertThat(tool.riskLevel()).isEqualTo("HIGH");
            assertThat(tool.capability()).isEqualTo("EXECUTE");
            assertThat(tool.inputSchema()).containsEntry("additionalProperties", false);
        });
        assertThat(tools.targetEnvironment(descriptor.name())).startsWith("MCP:").endsWith("@localhost");
        assertThat(tools.execute(descriptor.name(), "{\"id\":\"ISSUE-1\"}").output()).isEqualTo("issue result");
    }
}

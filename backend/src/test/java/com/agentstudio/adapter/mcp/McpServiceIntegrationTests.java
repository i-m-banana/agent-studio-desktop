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
    @MockitoBean StreamableHttpMcpClient client;

    @Test
    void syncAddsNamespacedHighRiskToolToUnifiedRegistry() throws Exception {
        when(client.transport()).thenReturn("STREAMABLE_HTTP");
        when(client.discover(any())).thenReturn(new McpDiscovery("2025-06-18", "test-server", "1.0", List.of(
                new McpRemoteTool("lookup.issue", "Lookup issue", "Read issue by id",
                        Map.of("type", "object", "properties", Map.of("id", Map.of("type", "string")),
                                "required", List.of("id"), "additionalProperties", false)))));
        when(client.call(any(), any(), any())).thenReturn("issue result");
        var server = service.create(new McpServerRequest("mcp-" + UUID.randomUUID(), "http://localhost:39999/mcp", null));
        var result = service.sync(server.id());
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.toolCount()).isEqualTo(1);
        assertThat(result.added()).isEqualTo(1);
        var descriptor = tools.descriptors().stream().filter(tool -> "MCP".equals(tool.source())).findFirst().orElseThrow();
        assertThat(descriptor).satisfies(tool -> {
            assertThat(tool.name()).startsWith("mcp_");
            assertThat(tool.riskLevel()).isEqualTo("HIGH");
            assertThat(tool.capability()).isEqualTo("EXECUTE");
            assertThat(tool.inputSchema()).containsEntry("additionalProperties", false);
        });
        assertThat(tools.targetEnvironment(descriptor.name())).startsWith("MCP:").endsWith("@localhost");
        assertThat(tools.execute(descriptor.name(), "{\"id\":\"ISSUE-1\"}").output()).isEqualTo("issue result");

        var secondSync = service.sync(server.id());
        assertThat(secondSync.added()).isZero();
        assertThat(secondSync.removed()).isZero();
        assertThat(secondSync.unchanged()).isEqualTo(1);
        service.updateToolPolicy(descriptor.name(), new McpToolPolicyRequest(false, "READ", "LOW", 12));
        assertThat(service.activeDescriptors()).noneMatch(tool -> tool.name().equals(descriptor.name()));
        service.setEnabled(server.id(), false);
        assertThat(service.listServers()).filteredOn(item -> item.id().equals(server.id()))
                .singleElement().extracting(McpServer::enabled).isEqualTo(false);

        var disposable = service.create(new McpServerRequest("delete-" + UUID.randomUUID(),
                "http://localhost:39998/mcp", null));
        service.delete(disposable.id());
        assertThat(service.listServers()).noneMatch(item -> item.id().equals(disposable.id()));
    }
}

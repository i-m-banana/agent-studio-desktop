package com.agentstudio.adapter.mcp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.agentstudio.knowledge.KnowledgeDocument;
import com.agentstudio.knowledge.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(McpController.class)
class McpControllerTests {
    @Autowired MockMvc mockMvc;
    @MockitoBean McpService service;
    @MockitoBean KnowledgeService knowledge;

    @Test
    void exportsConfigurationWithoutSecretValue() throws Exception {
        when(service.configuration("server-1")).thenReturn(new McpServerRequest(
                "project tools", "STREAMABLE_HTTP", "https://mcp.example.test/mcp", "MCP_TOKEN",
                null, List.of(), null, java.util.Map.of()));
        mockMvc.perform(get("/api/mcp/servers/server-1/configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyEnv").value("MCP_TOKEN"))
                .andExpect(jsonPath("$.endpointUrl").value("https://mcp.example.test/mcp"));
    }

    @Test
    void importsTextResourceIntoSelectedKnowledgeBase() throws Exception {
        var resource = new McpCatalogResource("resource-1", "server-1", "project://guide", "guide",
                "验收说明", "guide", "text/markdown", 20L, true, Instant.now());
        when(service.requireResource("server-1", "resource-1")).thenReturn(resource);
        when(service.readResource("server-1", "resource-1")).thenReturn(List.of(
                new McpResourceContent("project://guide", "text/markdown", "# Verified guide", null)));
        when(knowledge.importContent(any(), any(), any(), any())).thenReturn(new KnowledgeDocument(
                "doc-1", "kb-1", "验收说明.md", "text/markdown", 16, "hash", "stored", "READY", 1,
                null, Instant.now(), Instant.now()));

        mockMvc.perform(post("/api/mcp/servers/server-1/resources/resource-1/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgeBaseId\":\"kb-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.knowledgeBaseId").value("kb-1"))
                .andExpect(jsonPath("$.status").value("READY"));
        verify(knowledge).importContent(eq("kb-1"), eq("验收说明.md"), eq("text/markdown"),
                eq("# Verified guide".getBytes(StandardCharsets.UTF_8)));
    }
}

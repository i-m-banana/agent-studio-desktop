package com.agentstudio.adapter.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

public interface McpTransportClient {
    String transport();
    McpDiscovery discover(McpServer server) throws Exception;
    String call(McpServer server, McpCatalogTool tool, JsonNode arguments) throws Exception;
    List<McpResourceContent> readResource(McpServer server, String uri) throws Exception;
    McpPromptResult getPrompt(McpServer server, String name, Map<String, String> arguments) throws Exception;
}

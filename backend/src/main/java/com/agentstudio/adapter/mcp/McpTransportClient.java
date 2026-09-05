package com.agentstudio.adapter.mcp;

import com.fasterxml.jackson.databind.JsonNode;

public interface McpTransportClient {
    McpDiscovery discover(McpServer server) throws Exception;
    String call(McpServer server, McpCatalogTool tool, JsonNode arguments) throws Exception;
}

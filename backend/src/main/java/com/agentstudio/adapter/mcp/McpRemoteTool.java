package com.agentstudio.adapter.mcp;

import java.util.Map;

public record McpRemoteTool(String name, String title, String description, Map<String, Object> inputSchema) {
}

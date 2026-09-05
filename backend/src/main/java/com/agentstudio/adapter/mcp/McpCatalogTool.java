package com.agentstudio.adapter.mcp;

import java.time.Instant;
import java.util.Map;

import com.agentstudio.tool.ToolDescriptor;

public record McpCatalogTool(
        String publicName,
        String serverId,
        String remoteName,
        String displayName,
        String description,
        Map<String, Object> inputSchema,
        String capability,
        String riskLevel,
        int timeoutSeconds,
        boolean active,
        boolean enabled,
        String schemaSha256,
        Instant discoveredAt) {

    public McpCatalogTool(String publicName, String serverId, String remoteName, String displayName,
                          String description, Map<String, Object> inputSchema, String capability,
                          String riskLevel, int timeoutSeconds, boolean active, String schemaSha256,
                          Instant discoveredAt) {
        this(publicName, serverId, remoteName, displayName, description, inputSchema, capability,
                riskLevel, timeoutSeconds, active, true, schemaSha256, discoveredAt);
    }

    public ToolDescriptor descriptor() {
        return new ToolDescriptor(publicName, displayName, description, "MCP", capability,
                riskLevel, timeoutSeconds, inputSchema);
    }
}

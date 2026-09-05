package com.agentstudio.adapter.mcp;

import java.util.List;

public record McpDiscovery(String protocolVersion, String serverName, String serverVersion,
                           List<McpRemoteTool> tools, List<McpRemoteResource> resources,
                           List<McpRemotePrompt> prompts) {
    public McpDiscovery(String protocolVersion, String serverName, String serverVersion,
                        List<McpRemoteTool> tools) {
        this(protocolVersion, serverName, serverVersion, tools, List.of(), List.of());
    }
}

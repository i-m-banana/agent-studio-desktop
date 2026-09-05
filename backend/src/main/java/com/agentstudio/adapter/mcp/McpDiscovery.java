package com.agentstudio.adapter.mcp;

import java.util.List;

public record McpDiscovery(String protocolVersion, String serverName, String serverVersion,
                           List<McpRemoteTool> tools) {
}

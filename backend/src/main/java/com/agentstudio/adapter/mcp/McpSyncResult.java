package com.agentstudio.adapter.mcp;

public record McpSyncResult(String serverId, String status, String protocolVersion,
                            String remoteServerName, int toolCount, int resourceCount, int promptCount,
                            int added, int removed, int unchanged) {
}

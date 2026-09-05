package com.agentstudio.adapter.mcp;

import java.time.Instant;
import java.util.List;

public record McpServer(
        String id,
        String name,
        String endpointUrl,
        String apiKeyEnv,
        boolean enabled,
        String status,
        String protocolVersion,
        String remoteServerName,
        String remoteServerVersion,
        String lastError,
        Instant lastSyncedAt,
        Instant createdAt,
        Instant updatedAt,
        List<McpCatalogTool> tools) {
}

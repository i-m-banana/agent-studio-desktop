package com.agentstudio.adapter.mcp;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record McpServer(
        String id,
        String name,
        String transport,
        String endpointUrl,
        String apiKeyEnv,
        String command,
        List<String> arguments,
        String workingDirectory,
        Map<String, String> environment,
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

    public McpServer(String id, String name, String endpointUrl, String apiKeyEnv, boolean enabled,
                     String status, String protocolVersion, String remoteServerName, String remoteServerVersion,
                     String lastError, Instant lastSyncedAt, Instant createdAt, Instant updatedAt,
                     List<McpCatalogTool> tools) {
        this(id, name, "STREAMABLE_HTTP", endpointUrl, apiKeyEnv, null, List.of(), null, Map.of(), enabled,
                status, protocolVersion, remoteServerName, remoteServerVersion, lastError, lastSyncedAt,
                createdAt, updatedAt, tools);
    }
}

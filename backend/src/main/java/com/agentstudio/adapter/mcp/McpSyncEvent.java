package com.agentstudio.adapter.mcp;

import java.time.Instant;

public record McpSyncEvent(
        String id,
        String serverId,
        String status,
        String protocolVersion,
        int toolCount,
        int resourceCount,
        int promptCount,
        int added,
        int removed,
        int unchanged,
        String errorMessage,
        Instant createdAt) {}

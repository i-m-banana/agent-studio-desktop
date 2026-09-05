package com.agentstudio.adapter.mcp;

import java.time.Instant;

public record McpCatalogResource(String publicId, String serverId, String uri, String name,
                                 String displayName, String description, String mimeType,
                                 Long size, boolean active, Instant discoveredAt) {}

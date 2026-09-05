package com.agentstudio.adapter.mcp;

import java.time.Instant;
import java.util.List;

public record McpCatalogPrompt(String publicName, String serverId, String remoteName,
                               String displayName, String description,
                               List<McpPromptArgument> arguments, boolean active,
                               String fingerprintSha256, Instant discoveredAt) {}

package com.agentstudio.adapter.mcp;

import java.util.List;

public record McpPromptResult(String description, List<McpPromptMessage> messages) {}

package com.agentstudio.adapter.mcp;

import com.fasterxml.jackson.databind.JsonNode;

public record McpPromptMessage(String role, JsonNode content) {}

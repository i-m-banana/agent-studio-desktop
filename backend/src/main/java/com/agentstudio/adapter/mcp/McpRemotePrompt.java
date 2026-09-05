package com.agentstudio.adapter.mcp;

import java.util.List;

public record McpRemotePrompt(String name, String title, String description,
                              List<McpPromptArgument> arguments) {}

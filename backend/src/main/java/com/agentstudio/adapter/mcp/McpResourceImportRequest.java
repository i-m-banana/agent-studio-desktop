package com.agentstudio.adapter.mcp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record McpResourceImportRequest(@NotBlank String knowledgeBaseId,
                                       @Size(max = 240) String fileName) {}

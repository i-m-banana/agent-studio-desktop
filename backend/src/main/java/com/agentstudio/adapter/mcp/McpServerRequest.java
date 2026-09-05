package com.agentstudio.adapter.mcp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record McpServerRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 1000) String endpointUrl,
        @Size(max = 160) String apiKeyEnv) {
}

package com.agentstudio.adapter.mcp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record McpServerRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 40) String transport,
        @Size(max = 1000) String endpointUrl,
        @Size(max = 160) String apiKeyEnv,
        @Size(max = 1000) String command,
        List<@Size(max = 1000) String> arguments,
        @Size(max = 1000) String workingDirectory,
        Map<@Size(max = 160) String, @Size(max = 160) String> environment) {

    public McpServerRequest(String name, String endpointUrl, String apiKeyEnv) {
        this(name, "STREAMABLE_HTTP", endpointUrl, apiKeyEnv, null, List.of(), null, Map.of());
    }
}

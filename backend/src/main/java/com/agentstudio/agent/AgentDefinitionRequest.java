package com.agentstudio.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentDefinitionRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description,
        @NotBlank String modelProfileId,
        @NotBlank @Size(max = 20000) String systemPrompt) {
}


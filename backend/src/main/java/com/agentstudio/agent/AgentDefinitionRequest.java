package com.agentstudio.agent;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentDefinitionRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description,
        @NotBlank String modelProfileId,
        String knowledgeBaseId,
        @NotBlank @Size(max = 20000) String systemPrompt,
        List<@Size(max = 120) String> toolNames) {
}

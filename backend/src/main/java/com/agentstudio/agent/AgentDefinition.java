package com.agentstudio.agent;

import java.time.Instant;

public record AgentDefinition(
        String id,
        String name,
        String description,
        String draftModelProfileId,
        String draftKnowledgeBaseId,
        String draftSystemPrompt,
        int latestVersionNumber,
        Instant createdAt,
        Instant updatedAt) {

    @com.fasterxml.jackson.annotation.JsonProperty("status")
    public String status() {
        return latestVersionNumber == 0 ? "DRAFT" : "PUBLISHED";
    }
}

package com.agentstudio.agent;

import java.time.Instant;
import java.util.List;

public record AgentDefinition(
        String id,
        String name,
        String description,
        String draftModelProfileId,
        String draftKnowledgeBaseId,
        String draftSystemPrompt,
        List<String> draftToolNames,
        int latestVersionNumber,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt) {

    public AgentDefinition(String id, String name, String description, String draftModelProfileId,
                           String draftKnowledgeBaseId, String draftSystemPrompt, List<String> draftToolNames,
                           int latestVersionNumber, Instant createdAt, Instant updatedAt) {
        this(id,name,description,draftModelProfileId,draftKnowledgeBaseId,draftSystemPrompt,draftToolNames,
                latestVersionNumber,createdAt,updatedAt,null);
    }

    @com.fasterxml.jackson.annotation.JsonProperty("status")
    public String status() {
        return archivedAt != null ? "ARCHIVED" : latestVersionNumber == 0 ? "DRAFT" : "PUBLISHED";
    }
}

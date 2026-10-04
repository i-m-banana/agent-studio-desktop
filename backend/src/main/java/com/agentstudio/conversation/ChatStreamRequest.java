package com.agentstudio.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatStreamRequest(
        @NotBlank String agentVersionId,
        String conversationId,
        @NotBlank @Size(max = 20000) String message,
        @jakarta.validation.Valid RequestedTool requestedTool,
        String localProjectId) {
    public ChatStreamRequest(String agentVersionId, String conversationId, String message, RequestedTool requestedTool) {
        this(agentVersionId, conversationId, message, requestedTool, null);
    }
    public ChatStreamRequest(String agentVersionId, String conversationId, String message) {
        this(agentVersionId, conversationId, message, null, null);
    }

    public record RequestedTool(@NotBlank String name,
                                @jakarta.validation.constraints.NotNull com.fasterxml.jackson.databind.JsonNode arguments) {}
}

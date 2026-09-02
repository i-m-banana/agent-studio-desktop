package com.agentstudio.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatStreamRequest(
        @NotBlank String agentVersionId,
        String conversationId,
        @NotBlank @Size(max = 20000) String message) {
}


package com.agentstudio.runtime;

import java.time.Instant;

public record RunSummary(
        String id,
        String conversationId,
        String agentVersionId,
        String status,
        Instant startedAt,
        Instant completedAt,
        String errorMessage,
        int stepCount,
        String preview,
        String agentName) {
}

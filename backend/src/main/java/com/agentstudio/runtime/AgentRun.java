package com.agentstudio.runtime;

import java.time.Instant;
import java.util.List;

public record AgentRun(
        String id,
        String conversationId,
        String agentVersionId,
        String status,
        Instant startedAt,
        Instant completedAt,
        String errorMessage,
        List<RunStep> steps) {
}

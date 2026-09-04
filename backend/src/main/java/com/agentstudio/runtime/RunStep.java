package com.agentstudio.runtime;

import java.time.Instant;

public record RunStep(
        String id,
        String runId,
        int stepNumber,
        String stepType,
        String status,
        String toolCallId,
        String toolName,
        String inputJson,
        String outputText,
        Long durationMs,
        Instant createdAt) {
}

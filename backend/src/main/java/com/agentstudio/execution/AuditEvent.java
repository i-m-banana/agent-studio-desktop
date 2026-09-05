package com.agentstudio.execution;

import java.time.Instant;

public record AuditEvent(
        String id,
        String runId,
        String conversationId,
        String agentVersionId,
        String eventType,
        String toolName,
        String capability,
        String riskLevel,
        String status,
        String argumentsSha256,
        String details,
        Instant createdAt) {
}

package com.agentstudio.approval;

import java.time.Instant;

public record ApprovalRequest(
        String id, String runId, String toolCallId, String toolName,
        String argumentsJson, String argumentsSha256, String status, String reason,
        Instant createdAt, Instant expiresAt, Instant decidedAt) {
}

package com.agentstudio.approval;

import java.time.Instant;

public record ApprovalRequest(
        String id, String runId, String conversationId, String agentVersionId,
        String toolCallId, String toolName, String capability, String riskLevel, String targetEnvironment,
        String argumentsJson, String argumentsSha256, String status, String reason,
        Instant createdAt, Instant expiresAt, Instant decidedAt) {
}

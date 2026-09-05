package com.agentstudio.execution;

import com.agentstudio.approval.ApprovalOutcome;
import com.agentstudio.approval.ApprovalRequest;

public record SafeExecutionResult(
        boolean executed,
        String output,
        Long durationMs,
        ApprovalRequest approval,
        ApprovalOutcome approvalOutcome) {
}

package com.agentstudio.approval;

import jakarta.validation.constraints.Size;

public record ApprovalDecisionRequest(@Size(max = 500) String reason) {
}

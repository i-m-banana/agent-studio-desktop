package com.agentstudio.approval;

public record ApprovalOutcome(String status, String reason) {
    public boolean approved() {
        return "APPROVED".equals(status);
    }
}

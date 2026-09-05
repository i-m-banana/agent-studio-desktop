package com.agentstudio.execution;

import com.agentstudio.approval.ApprovalRequest;

@FunctionalInterface
public interface ApprovalListener {
    void requested(ApprovalRequest approval) throws Exception;
}

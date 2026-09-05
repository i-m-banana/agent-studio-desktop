package com.agentstudio.runtime;

import com.agentstudio.approval.ApprovalService;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;

@Service
public class RunRecoveryService {
    private final RunRepository runs;
    private final ApprovalService approvals;

    public RunRecoveryService(RunRepository runs, ApprovalService approvals) {
        this.runs = runs;
        this.approvals = approvals;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void closeInterruptedRuns() {
        var reason = "应用重启，上一进程中的运行无法继续";
        approvals.expireAllPending(reason);
        runs.interruptUnfinished(reason);
    }
}

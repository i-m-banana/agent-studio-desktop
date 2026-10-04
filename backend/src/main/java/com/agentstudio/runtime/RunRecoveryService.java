package com.agentstudio.runtime;

import com.agentstudio.approval.ApprovalService;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;

@Service
public class RunRecoveryService {
    private final RunRepository runs;
    private final ApprovalService approvals;
    private final com.agentstudio.release.ReleaseTaskRepository releaseTasks;
    private final com.agentstudio.conversation.ConversationRepository conversations;

    public RunRecoveryService(RunRepository runs, ApprovalService approvals,
                              com.agentstudio.release.ReleaseTaskRepository releaseTasks,
                              com.agentstudio.conversation.ConversationRepository conversations) {
        this.runs = runs;
        this.approvals = approvals;
        this.releaseTasks = releaseTasks;
        this.conversations = conversations;
    }

    @EventListener(ApplicationReadyEvent.class)
    @org.springframework.transaction.annotation.Transactional
    public void closeInterruptedRuns() {
        var reason = "后台进程中断，服务已恢复；原任务不会自动续跑。已完成的操作保留，未取得结果的工具不能算作完成。";
        approvals.expireAllPending(reason);
        for (var run : runs.unfinished()) {
            if (!runs.finish(run.id(), "INTERRUPTED", reason)) continue;
            var coding = run.steps().stream().anyMatch(step -> step.toolName() != null
                    && (step.toolName().contains("workspace") || step.toolName().equals("start_project_preview")));
            var message = coding ? com.agentstudio.conversation.InterruptedCodingProgress.summarize(run, reason)
                    : reason + "\n\n请先核对运行记录和目标的实际状态。没有回执的操作结果未知，不会自动执行工具或复用审批。";
            runs.addStep(run.id(), "RUN_TERMINATION", "INTERRUPTED", null, null, null, message, null);
            conversations.addMessage(run.conversationId(), "assistant", message);
        }
        releaseTasks.backfill();
    }
}

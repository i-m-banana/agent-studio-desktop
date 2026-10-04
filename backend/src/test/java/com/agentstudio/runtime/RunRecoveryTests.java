package com.agentstudio.runtime;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.agentstudio.approval.ApprovalService;
import com.agentstudio.conversation.ConversationRepository;
import com.agentstudio.release.ReleaseTaskRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunRecoveryTests {
    @Test void restoresInterruptedRunWithUnconfirmedVerificationAndDoesNotDuplicateMessage() {
        var runs=mock(RunRepository.class);var approvals=mock(ApprovalService.class);
        var tasks=mock(ReleaseTaskRepository.class);var conversations=mock(ConversationRepository.class);
        var time=Instant.now();
        var call=new RunStep("s","r",1,"TOOL_CALL","RUNNING","call","run_workspace_verification",
                "{\"task\":\"MYSQL_INTEGRATION\"}",null,null,time);
        var run=new AgentRun("r","c","v","TOOL_RUNNING",time,null,null,List.of(call));
        when(runs.unfinished()).thenReturn(List.of(run));
        when(runs.finish(eq("r"),eq("INTERRUPTED"),anyString())).thenReturn(true,false);
        var recovery=new RunRecoveryService(runs,approvals,tasks,conversations);
        recovery.closeInterruptedRuns();recovery.closeInterruptedRuns();
        verify(conversations,times(1)).addMessage(eq("c"),eq("assistant"),argThat(message->
                message.contains("后台进程中断")&&message.contains("没有取得执行结果")
                        &&message.contains("run_workspace_verification")&&message.contains("不会自动重跑")));
        verify(runs,times(1)).addStep(eq("r"),eq("RUN_TERMINATION"),eq("INTERRUPTED"),isNull(),isNull(),isNull(),anyString(),isNull());
        verify(approvals,times(2)).expireAllPending(anyString());
    }
    @Test void noUnfinishedRunProducesNoRecoveryMessage() {
        var runs=mock(RunRepository.class);var approvals=mock(ApprovalService.class);
        var tasks=mock(ReleaseTaskRepository.class);var conversations=mock(ConversationRepository.class);
        when(runs.unfinished()).thenReturn(List.of());
        new RunRecoveryService(runs,approvals,tasks,conversations).closeInterruptedRuns();
        verifyNoInteractions(conversations);
        verify(runs,never()).finish(anyString(),anyString(),anyString());
    }
}

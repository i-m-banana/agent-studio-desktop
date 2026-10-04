package com.agentstudio.runtime;

import com.agentstudio.system.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunRepository runs;
    private final RunControlService controls;
    private final com.agentstudio.approval.ApprovalRepository approvals;

    public RunController(RunRepository runs, RunControlService controls, com.agentstudio.approval.ApprovalRepository approvals) {
        this.runs = runs;
        this.controls = controls;
        this.approvals = approvals;
    }

    public record ActivityStep(String stepType,String toolName,java.time.Instant createdAt) {}
    public record Activity(String id,String status,java.time.Instant startedAt,ActivityStep lastStep,
                           com.agentstudio.approval.ApprovalRequest pendingApproval,java.time.Instant checkedAt) {}

    @GetMapping("/{id}/activity")
    Activity activity(@PathVariable String id) {
        var run = runs.find(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,"运行记录不存在"));
        var active = !java.util.Set.of("COMPLETED","FAILED","CANCELLED","TIMED_OUT","INTERRUPTED","CANCEL_REQUESTED").contains(run.status());
        var pending = active ? approvals.findPendingByRun(id).stream()
                .filter(a -> a.expiresAt().isAfter(java.time.Instant.now())).findFirst().orElse(null) : null;
        var last = run.steps().isEmpty() ? null : run.steps().getLast();
        return new Activity(id,run.status(),run.startedAt(),last == null ? null : new ActivityStep(last.stepType(),last.toolName(),last.createdAt()),pending,java.time.Instant.now());
    }

    @GetMapping
    java.util.List<RunSummary> list(@RequestParam(defaultValue = "50") int limit,
                                   @RequestParam(defaultValue = "0") int offset,
                                   @RequestParam(defaultValue = "") String query,
                                   @RequestParam(defaultValue = "") String status) {
        return runs.list(limit, offset, query, status);
    }

    @GetMapping("/{id}")
    AgentRun get(@PathVariable String id) {
        return runs.find(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "运行记录不存在"));
    }

    @PostMapping("/{id}/cancel")
    AgentRun cancel(@PathVariable String id) {
        runs.find(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "运行记录不存在"));
        if (!runs.requestCancellation(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "运行已结束或取消请求已提交");
        }
        if (!controls.cancel(id, "用户主动取消运行") && controls.termination(id) == null) {
            runs.finish(id, "CANCELLED", "运行进程已不存在，取消请求已关闭记录");
        }
        return runs.find(id).orElseThrow();
    }
}

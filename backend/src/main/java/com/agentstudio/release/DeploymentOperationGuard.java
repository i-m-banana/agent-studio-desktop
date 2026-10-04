package com.agentstudio.release;

import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;
import com.agentstudio.model.ModelToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Single-instance target exclusion covers both workflows and existing atomic tools. */
@Component
public class DeploymentOperationGuard {
    private final ReentrantLock lock=new ReentrantLock();
    private final WorkflowStore store;private final WorkflowIdentity identity;private final WorkflowEvidenceService evidence;
    private final ObjectMapper json;
    public DeploymentOperationGuard(WorkflowStore store,WorkflowIdentity identity,WorkflowEvidenceService evidence,ObjectMapper json){this.store=store;this.identity=identity;this.evidence=evidence;this.json=json;}
    public AutoCloseable creationLease(){if(!lock.tryLock())throw new IllegalStateException("目标已有原子操作执行中，请等待原运行结束");return lock::unlock;}
    public void requireMutableProject(String project){if(store.projectFrozen(project))throw new IllegalStateException("项目已有发布任务，先结束任务再修改源码；不能让发布材料失去来源");}
    public AutoCloseable enter(String runId,ModelToolCall call)throws Exception{
        if(!ReleaseTaskRepository.TOOLS.contains(call.name()))return ()->{};
        if(!lock.tryLock())throw new IllegalStateException("同一部署目标已有操作正在执行，请观察原运行，不要重复操作");
        try {validate(runId,call);return lock::unlock;}catch(Exception e){lock.unlock();throw e;}
    }
    public void validate(String runId,ModelToolCall call)throws Exception{
        if(!ReleaseTaskRepository.TOOLS.contains(call.name()))return;
        var owner=store.owner(WorkflowIdentity.hash(identity.target()));
        var workflowId=store.workflowForRun(runId);
        if(owner!=null&&!owner.equals(workflowId))throw new IllegalStateException("这个目标已有发布任务，请在该任务中继续；独立操作不能绕过任务互斥");
        if(workflowId!=null) {
            var flow=store.get(workflowId);
            var recovery=store.members(workflowId).stream().filter(m->m.runId().equals(runId)&&m.callId().equals(call.id())&&m.phase().startsWith("RECOVERY_")).findFirst();
            if(recovery.isPresent()){
                identity.validateTarget(flow);var phase=recovery.get().phase();var task=phase.equals("RECOVERY_STATUS")?"RELEASE_STATUS":phase.equals("RECOVERY_HISTORY")?"DATABASE_BASELINE_STATUS":"SITE_HEALTH";
                if(!call.name().equals("inspect_remote_deployment")||!json.readTree(call.argumentsJson()).equals(json.valueToTree(java.util.Map.of("task",task))))throw new IllegalStateException("恢复阶段仅允许固定只读核查");
            }else identity.validate(flow);
            if(!runId.equals(flow.activeRunId()))throw new IllegalStateException("发布任务已失去本次执行权");
            if(call.name().equals("publish_remote_release")) {
                var next=WorkflowPlan.plan(evidence.collect(workflowId,call.id()),flow.target(),flow.sourceSha256(),Instant.now(),true);
                if(!next.phase().equals("PUBLISH")||!json.valueToTree(next.arguments()).equals(json.readTree(call.argumentsJson())))throw new IllegalStateException("审批等待期间上线材料已过期或变化，请更新对应证据后重新审批");
            }
        }
    }
}

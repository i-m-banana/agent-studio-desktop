package com.agentstudio.release;

import java.time.*;
import java.util.*;
import com.agentstudio.agent.AgentService;
import com.agentstudio.project.LocalProjectService;
import com.agentstudio.conversation.ConversationRepository;
import com.agentstudio.runtime.*;
import com.agentstudio.execution.SafeExecutionGateway;
import com.agentstudio.model.ModelToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import com.agentstudio.system.ApiException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ReleaseWorkflowService {
    public record Create(String projectId,String agentVersionId,String previewId,boolean previewAccepted){}
    public record Advance(int revision,String operationId,boolean riskAcknowledged,boolean recoveryReviewed){}
    public record View(WorkflowStore.Workflow workflow,WorkflowPlan.Next next,List<WorkflowStore.Member> members,Map<String,WorkflowPlan.Evidence> evidence){}
    private final WorkflowStore store;private final WorkflowIdentity identity;private final WorkflowEvidenceService evidence;
    private final AgentService agents;private final LocalProjectService projects;private final ConversationRepository conversations;
    private final com.agentstudio.coding.LocalPreviewService previews;private final RunRepository runs;private final SafeExecutionGateway gateway;
    private final RunControlService controls;private final TaskExecutor executor;private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired private DeploymentOperationGuard deploymentGuard;
    @org.springframework.beans.factory.annotation.Autowired private com.agentstudio.coding.MySqlVerificationRunner mysql;
    @org.springframework.beans.factory.annotation.Autowired private com.agentstudio.project.ProjectRuntimeService runtime;
    public ReleaseWorkflowService(WorkflowStore store,WorkflowIdentity identity,WorkflowEvidenceService evidence,AgentService agents,
        LocalProjectService projects,ConversationRepository conversations,com.agentstudio.coding.LocalPreviewService previews,
        RunRepository runs,SafeExecutionGateway gateway,RunControlService controls,@Qualifier("chatTaskExecutor") TaskExecutor executor,ObjectMapper json){
        this.store=store;this.identity=identity;this.evidence=evidence;this.agents=agents;this.projects=projects;this.conversations=conversations;
        this.previews=previews;this.runs=runs;this.gateway=gateway;this.controls=controls;this.executor=executor;this.json=json;
    }
    private static final Set<String> TERMINAL=Set.of("COMPLETED","FAILED","CANCELLED","TIMED_OUT","INTERRUPTED");
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverInterrupted(){for(var w:store.leased())if(w.activeRunId()!=null&&runs.find(w.activeRunId()).map(r->TERMINAL.contains(r.status())).orElse(true))store.save(w.change("NEEDS_REVIEW",null,"平台曾中断本次观察或执行；核对阶段回执后明确继续，不会自动续跑或复用批准",false));}
    private static void conflict(String message){throw new ApiException(HttpStatus.CONFLICT,message);}
    public List<View> list(String project){projects.get(project);return store.list(project).stream().map(w->view(w,false)).toList();}
    public View get(String id){return view(store.get(id),false);}
    private View view(WorkflowStore.Workflow w,boolean reviewed){
        var facts=evidence.collect(w.id(),null);
        var lastPublish=store.members(w.id()).stream().filter(m->m.phase().equals("PUBLISH")).reduce((a,b)->b).map(WorkflowStore.Member::callId).orElse(null);
        var next=WorkflowPlan.plan(facts,w.target(),w.sourceSha256(),Instant.now(),reviewed || lastPublish!=null&&lastPublish.equals(w.reviewedPublishCall()));
        if(w.status().equals("CLOSED")||w.businessAccepted())next=new WorkflowPlan.Next("CLOSED","任务已结束",w.message(),Map.of(),true,w.message());
        else try{validateFor(w,next);}catch(Exception e){next=new WorkflowPlan.Next("BLOCKED","项目或目标已变化",e.getMessage(),Map.of(),true,e.getMessage());}
        if(w.activeRunId()!=null&&runs.find(w.activeRunId()).map(r->!TERMINAL.contains(r.status())).orElse(false))next=new WorkflowPlan.Next("OBSERVE","观察原运行","本次准备仍在进行；刷新只恢复观察，不重新执行",Map.of(),true,"");
        return new View(w,next,store.members(w.id()),facts);
    }
    public synchronized View create(Create request)throws Exception {
        try(var lease=deploymentGuard.creationLease()) {
        var project=projects.get(request.projectId());var version=agents.getVersion(request.agentVersionId());
        if(version.archived()||agents.get(version.agentDefinitionId()).archivedAt()!=null)conflict("请选择未归档的部署助手版本");
        var required=Set.of("prepare_release_candidate","build_release_candidate_image","prepare_remote_deployment_backup","inspect_remote_deployment","publish_remote_release");
        if(!version.toolNames().containsAll(required))conflict("这个助手缺少完整发布能力，请在助手管理明确配置并发布版本");
        if(!projects.status(project).status().equals("READY"))conflict("项目保护范围尚未确认或目录不可用");
        projects.requireNoActiveRun(project.id());
        var source=identity.source(project.id());
        if(!request.previewAccepted()||previews.list(project.id()).stream().noneMatch(p->p.id().equals(request.previewId())&&p.sourceSha256().equals(source)&&Set.of("READY","STOPPED").contains(p.state())))conflict("请先人工验收当前源码的隔离预览；源码变化后不能复用旧预览");
        if(runtime.get(project.id()).mysqlEnabled()&&mysql.list(project.id()).stream().noneMatch(r->source.equals(r.get("sourceSha256"))&&Boolean.TRUE.equals(r.get("successful"))&&Boolean.TRUE.equals(r.get("allRequiredSuitesPassed"))&&Boolean.TRUE.equals(r.get("cleanupConfirmed"))))conflict("当前源码尚未通过 MySQL 合成集成验证；请在会话项目面板验证后再准备上线");
        var target=identity.target();var key=WorkflowIdentity.hash(target);
        if(store.owner(key)!=null)conflict("此目标已有发布任务，请先打开并处理原任务");
        var conversation=conversations.create(version.id());projects.bind(conversation,project.id());
        var w=new WorkflowStore.Workflow(UUID.randomUUID().toString(),project.id(),project.revision(),identity.workspaceIdentity(project.id()),source,conversation,version.id(),target,key,identity.targetIdentity(),"READY",1,null,"已创建独立发布任务；尚未执行操作",false,null,Instant.now());
        identity.validate(w);store.create(w);return get(w.id());
        }
    }
    public synchronized SseEmitter advance(String id,Advance request)throws Exception {
        synchronized(conversations) {
        var w=store.get(id);
        if(request.recoveryReviewed())conflict("请先使用异常核查的人工复核入口；这不会同时批准上线");
        if(request.operationId()==null||!request.operationId().matches("[0-9a-f-]{36}"))throw new IllegalArgumentException("操作身份无效");
        var prior=store.operationRun(request.operationId());if(prior!=null)conflict("这次请求已创建原运行 "+prior+"，请恢复观察，不重复执行");
        if(w.revision()!=request.revision())conflict("任务进度已变化，请刷新后继续");
        if(w.status().equals("CLOSED")||w.businessAccepted())conflict("任务已结束，不重复执行");
        if(w.activeRunId()!=null&&runs.find(w.activeRunId()).map(r->!TERMINAL.contains(r.status())).orElse(false))conflict("任务仍在执行，观察原运行");
        var next=view(w,request.recoveryReviewed()).next();
        validateFor(w,next);projects.requireNoActiveRun(w.projectId());
        if(next.blocked()||Set.of("ACCEPT","REVIEW","RECONCILE").contains(next.phase()))conflict(next.purpose());
        if(next.phase().equals("PUBLISH")&&!request.riskAcknowledged())conflict("正式上线需要先确认风险，再请求新的审批");
        var run=runs.start(w.conversationId(),w.agentVersionId());
        var active=w.change("RUNNING",run.id(),next.title()+"：等待逐项审批和执行",false);
        if(request.recoveryReviewed())active=active.reviewed(store.members(id).stream().filter(m->m.phase().equals("PUBLISH")).reduce((a,b)->b).map(WorkflowStore.Member::callId).orElse(null));
        try{store.dispatch(active,request.operationId(),run.id());}catch(Exception e){runs.finish(run.id(),"FAILED","任务派发未确认，未执行工具");throw e;}
        conversations.addMessage(w.conversationId(),"user",next.phase().equals("PUBLISH")?"确认本任务正式上线，逐项审批仍有效":"继续本任务准备；逐项审批，不直接上线");
        var emitter=new SseEmitter(1810000L);
        boolean publish=next.phase().equals("PUBLISH");
        var dispatched=active;
        try{executor.execute(()->execute(dispatched,run,emitter,publish,request.recoveryReviewed()));}
        catch(RuntimeException e){runs.finish(run.id(),"FAILED","未能启动本地派发线程，工具未执行");finish(id,"NEEDS_REVIEW","未启动执行线程，请核对后重新发起");throw e;}
        return emitter;
        }
    }
    public synchronized View review(String id,int revision)throws Exception{
        var w=store.get(id);if(w.revision()!=revision)conflict("任务已变化，请刷新");
        identity.validate(w);
        if(!get(id).next().phase().equals("REVIEW"))conflict("先完成异常后的只读核查，再记录人工复核");
        var call=store.members(id).stream().filter(m->m.phase().equals("PUBLISH")).reduce((a,b)->b).orElseThrow().callId();
        store.save(w.change("PAUSED",null,"已记录人工复核；继续准备和正式上线仍需新操作及审批",false).reviewed(call));return get(id);
    }
    public synchronized View accept(String id,int revision){
        var w=store.get(id);if(w.revision()!=revision)conflict("任务已变化，请刷新");
        if(!get(id).next().phase().equals("ACCEPT"))conflict("上线后核查尚未完成，不能记录验收通过");
        store.release(w.change("CLOSED",null,"用户已完成本次业务人工验收；不意味着真实故障演练通过",true));return get(id);
    }
    public synchronized View close(String id,int revision){
        var w=store.get(id);if(w.revision()!=revision)conflict("任务已变化，请刷新");
        if(w.activeRunId()!=null&&runs.find(w.activeRunId()).map(r->!TERMINAL.contains(r.status())).orElse(false))conflict("先取消并确认原运行停止，再结束任务");
        var facts=evidence.collect(id,null);var publish=facts.get("PUBLISH");
        if(publish!=null&&!publish.status().equals("DEPLOYED")){
            var plan=WorkflowPlan.plan(facts,w.target(),w.sourceSha256(),Instant.now(),true);
            if(plan.phase().startsWith("RECOVERY")||plan.blocked())conflict("上次上线影响尚未安全核实，先完成只读核查，不能释放目标占用");
        }
        store.release(w.change("CLOSED",null,"用户结束任务；历史、候选和所有备份保留，没有撤销已执行操作",false));return get(id);
    }
    private void send(SseEmitter emitter,String event,Object value){try{emitter.send(SseEmitter.event().name(event).data(value));}catch(Exception ignored){/* disconnected observers never trigger a replay */}}
    private void validateFor(WorkflowStore.Workflow w,WorkflowPlan.Next next)throws Exception{
        if(next.phase().startsWith("RECOVERY_"))identity.validateTarget(w);else identity.validate(w);
    }
    private void execute(WorkflowStore.Workflow w,AgentRun run,SseEmitter emitter,boolean publishApproved,boolean reviewed){
        controls.register(run.id(),Thread.currentThread(),Duration.ofMinutes(30));
        send(emitter,"run",Map.of("runId",run.id(),"conversationId",w.conversationId(),"workflowId",w.id(),"agentVersionId",w.agentVersionId()));
        try{
            for(int count=0;count<12;count++) {
                controls.check(run.id());
                var lastPublish=store.members(w.id()).stream().filter(m->m.phase().equals("PUBLISH")).reduce((a,b)->b).map(WorkflowStore.Member::callId).orElse(null);
                var next=WorkflowPlan.plan(evidence.collect(w.id(),null),w.target(),w.sourceSha256(),Instant.now(),reviewed || lastPublish!=null&&lastPublish.equals(w.reviewedPublishCall()));
                validateFor(w,next);
                if(next.blocked())throw new IllegalStateException(next.reason());
                if(Set.of("ACCEPT","REVIEW","RECONCILE").contains(next.phase()) || next.phase().equals("PUBLISH")&&!publishApproved)break;
                if(next.phase().equals("PUBLISH"))publishApproved=false; // one explicit production intent per advance
                var tool=WorkflowPlan.tool(next.phase());
                if(!agents.getVersion(w.agentVersionId()).toolNames().contains(tool))throw new IllegalStateException("固定助手版本未绑定本步骤工具");
                var call=new ModelToolCall("workflow-"+UUID.randomUUID(),tool,json.writeValueAsString(next.arguments()));
                store.member(w.id(),run.id(),call.id(),next.phase());
                send(emitter,"step",runs.addStep(run.id(),"TOOL_CALL","RUNNING",call.id(),tool,call.argumentsJson(),null,null));
                runs.updateStatus(run.id(),"TOOL_RUNNING");
                var result=gateway.execute(run.id(),w.conversationId(),w.agentVersionId(),call,approval->{
                    runs.updateStatus(run.id(),"WAITING_APPROVAL");send(emitter,"step",runs.addStep(run.id(),"APPROVAL_REQUEST","WAITING",call.id(),tool,call.argumentsJson(),null,null));send(emitter,"approval_required",approval);
                },()->runs.updateStatus(run.id(),"TOOL_RUNNING"),()->controls.check(run.id()));
                if(result.approvalOutcome()!=null)send(emitter,"step",runs.addStep(run.id(),"APPROVAL_RESULT",result.approvalOutcome().status(),call.id(),tool,null,result.approvalOutcome().reason(),null));
                if(!result.executed()) {send(emitter,"step",runs.addStep(run.id(),"TOOL_RESULT","NOT_EXECUTED",call.id(),tool,null,result.output(),null));throw new IllegalStateException("本步骤未获批准，已停止后续准备；没有自动推进");}
                send(emitter,"step",runs.addStep(run.id(),"TOOL_RESULT","COMPLETED",call.id(),tool,null,result.output(),result.durationMs()));
                var receipt="固定任务工具已执行；以下为原始工具结果（按工具类型核实结果），不是模型推测：\n\n```json\n"+result.output()+"\n```";
                conversations.addMessage(w.conversationId(),"assistant",receipt);send(emitter,"workflow_receipt",Map.of("content",receipt));
                var fact=evidence.collect(w.id(),null).get(next.phase());
                if(fact==null||!Set.of("SUCCEEDED","DEPLOYED").contains(fact.status()))throw new IllegalStateException(next.title()+"未成功或结果未确认，已停止；核对本阶段回执再处理");
            }
            runs.finish(run.id(),"COMPLETED",null);
            finish(w.id(),"PAUSED","本次阶段已结束；下一步需要明确操作，不自动上线");
            send(emitter,"delta",Map.of("content","本次阶段已结束，请查看发布任务的下一步。会话结束不代表整项需求或人工验收完成。"));send(emitter,"done",Map.of("runId",run.id()));emitter.complete();
        }catch(Exception e){
            var termination=controls.termination(run.id());var reason=termination==null?(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()):termination.reason();
            runs.finish(run.id(),termination==null?"FAILED":termination.status(),reason);finish(w.id(),"NEEDS_REVIEW",reason);
            send(emitter,"error",Map.of("message",reason));emitter.complete();
        }finally{controls.unregister(run.id());}
    }
    private synchronized void finish(String id,String state,String message){var w=store.get(id);store.save(w.change(state,null,message,false));}
}

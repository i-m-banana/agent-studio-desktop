package com.agentstudio.release;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import com.agentstudio.agent.*;
import com.agentstudio.model.*;
import com.agentstudio.project.*;
import com.agentstudio.coding.LocalPreviewService;
import com.agentstudio.execution.*;
import com.agentstudio.runtime.*;
import com.agentstudio.approval.ApprovalOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class WorkflowDispatchTests {
    @Autowired ReleaseWorkflowService service;@Autowired WorkflowStore store;@Autowired RunRepository runs;
    @Autowired AuditRepository audits;@Autowired AgentService agents;@Autowired ModelProfileService models;@Autowired ObjectMapper json;
    @Autowired DeploymentOperationGuard guard;
    @Autowired com.agentstudio.conversation.ConversationRepository conversations;
    @MockitoBean WorkflowIdentity identity;@MockitoBean LocalProjectService projects;@MockitoBean LocalPreviewService previews;@MockitoBean SafeExecutionGateway gateway;
    @MockitoBean com.agentstudio.coding.MySqlVerificationRunner mysql;@MockitoBean ProjectRuntimeService runtime;
    String target="fixture-"+UUID.randomUUID();String source="a".repeat(64);String projectId=UUID.randomUUID().toString();
    LocalProject project;
    @BeforeEach void prepare()throws Exception{
        project=new LocalProject(projectId,"合成项目","fixture",List.of("src"),List.of("uploads"),1,false,true);
        when(projects.get(projectId)).thenReturn(project);when(projects.status(project)).thenReturn(new LocalProjectService.Status(project,"READY","fixture"));
        when(identity.target()).thenReturn(target);when(identity.targetIdentity()).thenReturn(target);when(identity.workspaceIdentity(projectId)).thenReturn("fixture-root");when(identity.source(projectId)).thenReturn(source);
        when(previews.list(projectId)).thenReturn(List.of(new LocalPreviewService.Preview("preview",projectId,"STOPPED",source,null,0,null,null,null,"fixture")));
        when(runtime.get(projectId)).thenReturn(new ProjectRuntimeService.Configuration(projectId,0,"SHIGUANGXV_SPRING_BOOT_H2_V1",true,true,30));
        when(mysql.list(projectId)).thenReturn(List.of(new LinkedHashMap<>(Map.of("sourceSha256",source,"successful",true,"allRequiredSuitesPassed",true,"cleanupConfirmed",true))));
    }
    String version(){
        var suffix=UUID.randomUUID().toString();var model=models.create(new ModelProfileRequest("workflow-"+suffix,"OPENAI_COMPATIBLE","https://example.com/v1","test","TEST_KEY",new BigDecimal("0.2")));
        return agents.publish(agents.create(new AgentDefinitionRequest("workflow-"+suffix,"fixture",model.id(),null,"test",List.of("prepare_release_candidate","build_release_candidate_image","prepare_remote_deployment_backup","inspect_remote_deployment","publish_remote_release"))).id()).id();
    }
    ReleaseWorkflowService.View create()throws Exception{return service.create(new ReleaseWorkflowService.Create(projectId,version(),"preview",true));}
    @Test void previewAcceptanceAndIdentityCannotBeSkipped()throws Exception{
        assertThatThrownBy(()->service.create(new ReleaseWorkflowService.Create(projectId,version(),"preview",false))).hasMessageContaining("人工验收");
        when(previews.list(projectId)).thenReturn(List.of());assertThatThrownBy(this::create).hasMessageContaining("源码变化");verifyNoInteractions(gateway);
    }
    @Test void sameTargetOnlyHasOneWorkflowAndClosingPreservesHistory()throws Exception{
        var v=create();assertThatThrownBy(this::create).hasMessageContaining("已有发布任务");service.close(v.workflow().id(),v.workflow().revision());assertThat(service.get(v.workflow().id()).workflow().status()).isEqualTo("CLOSED");assertThat(store.owner(v.workflow().targetKey())).isNull();verifyNoInteractions(gateway);
    }
    @Test void mysqlProofMustMatchCurrentSource()throws Exception{when(mysql.list(projectId)).thenReturn(List.of(new LinkedHashMap<>(Map.of("sourceSha256","b".repeat(64),"successful",true,"allRequiredSuitesPassed",true,"cleanupConfirmed",true))));assertThatThrownBy(this::create).hasMessageContaining("MySQL");verifyNoInteractions(gateway);}
    @Test void workflowLeaseAlsoBlocksIndependentAtomicToolsAndProjectWrites()throws Exception{var v=create();assertThatThrownBy(()->guard.enter("independent",new ModelToolCall("call","publish_remote_release","{}"))).hasMessageContaining("互斥");assertThatThrownBy(()->guard.requireMutableProject(projectId)).hasMessageContaining("先结束");assertThatThrownBy(()->conversations.moveToTrash(List.of(v.workflow().conversationId()),true)).hasMessageContaining("发布任务");service.close(v.workflow().id(),v.workflow().revision());try(var lease=guard.enter("independent",new ModelToolCall("call","inspect_remote_deployment","{}"))){guard.requireMutableProject(projectId);}conversations.moveToTrash(List.of(v.workflow().conversationId()),true);verifyNoInteractions(gateway);}
    @Test void restartPreservesUnknownAttemptAndOnlyOffersReadOnlyRecovery()throws Exception{
        var v=create();var run=runs.start(v.workflow().conversationId(),v.workflow().agentVersionId());var call="interrupted-call";
        store.dispatch(v.workflow().change("RUNNING",run.id(),"执行中",false),UUID.randomUUID().toString(),run.id());store.member(v.workflow().id(),run.id(),call,"PUBLISH");runs.addStep(run.id(),"TOOL_CALL","RUNNING",call,"publish_remote_release","{}",null,null);
        audits.add(run.id(),run.conversationId(),run.agentVersionId(),"TOOL_EXECUTION_STARTED","publish_remote_release","EXECUTE","HIGH","STARTED",WorkflowIdentity.hash("{}"),"fixture");runs.finish(run.id(),"INTERRUPTED","fixture restart");
        service.recoverInterrupted();var restored=service.get(v.workflow().id());assertThat(restored.workflow().activeRunId()).isNull();assertThat(restored.workflow().status()).isEqualTo("NEEDS_REVIEW");assertThat(restored.next().phase()).isEqualTo("RECOVERY_STATUS");assertThat(restored.evidence().get("PUBLISH").status()).isEqualTo("UNKNOWN");assertThat(store.owner(v.workflow().targetKey())).isEqualTo(v.workflow().id());verifyNoInteractions(gateway);
    }
    @Test void concurrentAdvanceRefreshAndRepeatedOperationNeverRedispatch()throws Exception{
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(gateway.execute(anyString(),anyString(),anyString(),any(),any(),any(),any())).thenAnswer(inv->{started.countDown();release.await(5,TimeUnit.SECONDS);ModelToolCall call=inv.getArgument(3);audits.add(inv.getArgument(0),inv.getArgument(1),inv.getArgument(2),"TOOL_EXECUTION_SKIPPED",call.name(),"EXECUTE","HIGH","REJECTED",WorkflowIdentity.hash(call.argumentsJson()),"fixture rejection");return new SafeExecutionResult(false,"审批拒绝",null,null,new ApprovalOutcome("REJECTED",null));});
        var v=create();String op=UUID.randomUUID().toString();service.advance(v.workflow().id(),new ReleaseWorkflowService.Advance(1,op,false,false));assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
        assertThat(service.get(v.workflow().id()).next().phase()).isEqualTo("OBSERVE");
        assertThatThrownBy(()->service.advance(v.workflow().id(),new ReleaseWorkflowService.Advance(1,op,false,false))).hasMessageContaining("原运行");
        assertThatThrownBy(()->service.advance(v.workflow().id(),new ReleaseWorkflowService.Advance(1,UUID.randomUUID().toString(),false,false))).hasMessageContaining("进度已变化");
        release.countDown();waitEnded(v.workflow().id());verify(gateway,times(1)).execute(anyString(),anyString(),anyString(),any(),any(),any(),any());assertThat(service.get(v.workflow().id()).next().phase()).isEqualTo("CANDIDATE");
    }
    @Test void sourceAndTargetChangesStopBeforeDispatch()throws Exception{
        var v=create();doThrow(new IllegalStateException("源码变化")).when(identity).validate(any());assertThatThrownBy(()->service.advance(v.workflow().id(),new ReleaseWorkflowService.Advance(1,UUID.randomUUID().toString(),false,false))).hasMessageContaining("源码变化");verifyNoInteractions(gateway);
    }
    @Test void automaticPreparationStopsAtFreshPublishIntentWithoutCallingPublish()throws Exception{
        when(gateway.execute(anyString(),anyString(),anyString(),any(),any(),any(),any())).thenAnswer(inv->{
            String run=inv.getArgument(0),conversation=inv.getArgument(1),version=inv.getArgument(2);ModelToolCall call=inv.getArgument(3);
            var map=new LinkedHashMap<String,Object>();map.put("successful",true);map.put("exitCode",0);map.put("target",target);map.put("outputTruncated",false);
            if(call.name().equals("prepare_release_candidate")){map.put("stage","REMOTE_VERIFY");map.put("releaseId","20261004T060000Z-1234abcd");map.put("manifestSha256","b".repeat(64));map.put("sourceSha256",source);}
            else if(call.name().equals("build_release_candidate_image")){map.putAll(json.convertValue(json.readTree(call.argumentsJson()),Map.class));map.put("stage","IMAGE_READY");map.put("imageId","sha256:"+"c".repeat(64));map.put("output","AGENTSTUDIO_STAGE=RUNTIME_SMOKE");}
            else if(call.name().equals("prepare_remote_deployment_backup")){map.put("backupId",java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now().minusSeconds(1))+"-9876abcd");map.put("manifestSha256","d".repeat(64));}
            else{String task=json.readTree(call.argumentsJson()).path("task").asText();map.put("task",task);if(task.equals("DATABASE_SCHEMA")){map.put("schemaComplete",true);map.put("schemaSha256","e".repeat(64));}if(task.equals("DATABASE_BASELINE_STATUS"))map.put("output","[\"1\",\"BASELINE\",1]");if(task.equals("RELEASE_STATUS")){map.put("currentImageId","sha256:"+"f".repeat(64));map.put("productionSha256","1".repeat(64));map.put("appHealth","healthy");}}
            for(var event:List.of("APPROVAL_DECIDED","TOOL_EXECUTION_STARTED","TOOL_EXECUTION_COMPLETED"))audits.add(run,conversation,version,event,call.name(),"EXECUTE","HIGH",event.equals("APPROVAL_DECIDED")?"APPROVED":"COMPLETED",WorkflowIdentity.hash(call.argumentsJson()),"fixture");
            return new SafeExecutionResult(true,json.writeValueAsString(map),1L,null,new ApprovalOutcome("APPROVED",null));
        });
        var v=create();service.advance(v.workflow().id(),new ReleaseWorkflowService.Advance(1,UUID.randomUUID().toString(),false,false));waitEnded(v.workflow().id());
        assertThat(service.get(v.workflow().id()).next().phase()).isEqualTo("PUBLISH");verify(gateway,times(6)).execute(anyString(),anyString(),anyString(),any(),any(),any(),any());
        var ready=service.get(v.workflow().id());assertThatThrownBy(()->service.advance(v.workflow().id(),new ReleaseWorkflowService.Advance(ready.workflow().revision(),UUID.randomUUID().toString(),false,false))).hasMessageContaining("确认风险");
    }
    void waitEnded(String id)throws Exception{for(int i=0;i<100;i++){if(store.get(id).activeRunId()==null)return;Thread.sleep(30);}throw new AssertionError("fixture workflow did not finish");}
}

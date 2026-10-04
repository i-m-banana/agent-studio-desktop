package com.agentstudio.coding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import com.agentstudio.approval.*;
import com.agentstudio.execution.*;
import com.agentstudio.model.ModelToolCall;
import com.agentstudio.project.*;
import com.agentstudio.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class PreviewApprovalTests {
    @TempDir Path root;
    LocalPreviewService previews;
    ApprovalService approvals;
    LocalProjectService projects;
    SafeExecutionGateway gateway;
    LocalProject project;
    ModelToolCall call=new ModelToolCall("call","start_project_preview","{}");
    @BeforeEach void prepare() throws Exception {
        Files.createDirectory(root.resolve("src"));Files.writeString(root.resolve("src/App.java"),"class App{}");
        project=new LocalProject("fixture","合成项目",root.toString(),List.of("src"),List.of("uploads"),1,false,true);
        projects=mock(LocalProjectService.class);when(projects.resolve("conversation",null)).thenReturn(project);when(projects.identity(project)).thenReturn("fixture-directory");
        previews=mock(LocalPreviewService.class);approvals=mock(ApprovalService.class);var mapper=new ObjectMapper();
        var registry=new ToolRegistry(List.of(new StartProjectPreviewTool(previews,mapper)),mapper);
        gateway=new SafeExecutionGateway(registry,new ToolInputValidator(mapper),approvals,mock(AuditRepository.class));
        ReflectionTestUtils.setField(gateway,"projects",projects);
        ReflectionTestUtils.setField(gateway,"codingWorkspace",new CodingWorkspace(root.toString()));
        var runner=mock(IsolatedProjectRunner.class);when(runner.identity()).thenReturn("offline-image-fixture");ReflectionTestUtils.setField(gateway,"isolatedRunner",runner);
        when(approvals.request(anyString(),anyString(),anyString(),any(),any(),anyString())).thenAnswer(invocation ->
            new ApprovalRequest("approval","run","conversation","version","call","start_project_preview","EXECUTE","HIGH",invocation.getArgument(5),"{}","hash","PENDING",null,Instant.now(),Instant.now().plusSeconds(30),null));
    }
    SafeExecutionResult execute(ModelToolCall requested) throws Exception {
        return gateway.execute("run","conversation","version",requested,a->{},()->{},()->{});
    }
    @Test void rejectedApprovalDoesNotStartPreview() throws Exception {
        when(approvals.await(any())).thenReturn(new ApprovalOutcome("REJECTED",null));
        assertThat(execute(call).executed()).isFalse();verifyNoInteractions(previews);
    }
    @Test void approvedSnapshotIsPassedToToolExecution() throws Exception {
        when(approvals.await(any())).thenReturn(new ApprovalOutcome("APPROVED",null));
        when(previews.start()).thenAnswer(invocation -> {
            assertThat(ProjectExecutionContext.current().id()).isEqualTo(project.id());
            assertThat(ProjectExecutionContext.sourceSha256()).matches("[0-9a-f]{64}");
            return new LocalPreviewService.Preview("preview",project.id(),"READY",ProjectExecutionContext.sourceSha256(),"http://127.0.0.2:12345",999,null,null,null,"fixture");
        });
        var result=execute(call);
        assertThat(result.executed()).isTrue();verify(previews).start();
        var receipt=new ObjectMapper().readTree(result.output());
        assertThat(receipt.path("task").asText()).isEqualTo("START_PROJECT_PREVIEW");
        assertThat(receipt.path("successful").asBoolean()).isTrue();
        assertThat(receipt.path("receiptVersion").asInt()).isEqualTo(1);
        assertThat(receipt.path("mysqlIntegrationTested").asBoolean()).isFalse();
        assertThat(receipt.has("exitCode")).isFalse();
        assertThat(receipt.path("productionModified").asBoolean()).isFalse();
    }
    @Test void sourceChangeWhileApprovalWaitsInvalidatesApproval() throws Exception {
        when(approvals.await(any())).thenAnswer(invocation -> {Files.writeString(root.resolve("src/App.java"),"class Changed{}");return new ApprovalOutcome("APPROVED",null);});
        assertThatThrownBy(()->execute(call)).hasMessageContaining("审批目标与当前执行目标不一致");verifyNoInteractions(previews);
    }
    @Test void unsupportedArgumentsCannotRequestApprovalOrStart() {
        assertThatThrownBy(()->execute(new ModelToolCall("call","start_project_preview","{\"database\":\"production\"}"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(approvals,previews);
    }
    @Test void unboundProjectCannotStart() throws Exception {
        when(projects.resolve("conversation",null)).thenReturn(null);
        assertThatThrownBy(()->execute(call)).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(approvals,previews);
    }
}

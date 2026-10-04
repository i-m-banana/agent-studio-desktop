package com.agentstudio.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
import java.util.function.Consumer;

import com.agentstudio.agent.AgentDefinitionRequest;
import com.agentstudio.agent.AgentService;
import com.agentstudio.adapter.ssh.SshWorkspaceProperties;
import com.agentstudio.adapter.ssh.SshWorkspaceService;
import com.agentstudio.knowledge.KnowledgeBaseRequest;
import com.agentstudio.knowledge.KnowledgeRetriever;
import com.agentstudio.knowledge.KnowledgeService;
import com.agentstudio.knowledge.RagSource;
import com.agentstudio.model.ModelProfileRequest;
import com.agentstudio.model.ModelProfileService;
import com.agentstudio.model.ModelToolCall;
import com.agentstudio.model.ModelTurn;
import com.agentstudio.model.StreamingModelGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@SpringBootTest(properties={"agent-studio.projects.allowed-roots=${user.dir}/target/test-coding-workspace", "agent-studio.runtime.coding-max-tool-rounds=6", "agent-studio.runtime.model-connect-retry-delay=1ms"})
@AutoConfigureMockMvc
class ChatStreamIntegrationTests {
    @Autowired private com.agentstudio.project.LocalProjectService localProjects;
    private String codingProject() {
        var existing=localProjects.list();
        if(!existing.isEmpty())return existing.getFirst().project().id();
        return localProjects.save(null,new com.agentstudio.project.LocalProjectService.Request("编码测试项目",
                java.nio.file.Path.of("target/test-coding-workspace").toAbsolutePath().normalize().toString(),
                List.of("src"),List.of("uploads","data"),null,true)).id();
    }

    @Test void fixedLocalVerificationUsesBoundProjectWithoutCallingModel() throws Exception {
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/test-coding-workspace/src"));
        when(knowledgeRetriever.retrieve(any(),any())).thenReturn(List.of());
        var suffix=UUID.randomUUID().toString();
        var model=modelProfiles.create(new ModelProfileRequest("fixed-local-"+suffix,"OPENAI_COMPATIBLE","https://example.com/v1","test","TEST_KEY",new BigDecimal("0.2")));
        var agent=agents.create(new AgentDefinitionRequest("fixed-local-"+suffix,"fixture",model.id(),null,"测试",List.of("run_workspace_verification")));
        var version=agents.publish(agent.id());
        var result=mockMvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"agentVersionId\":\"%s\",\"localProjectId\":\"%s\",\"message\":\"执行验证\",\"requestedTool\":{\"name\":\"run_workspace_verification\",\"arguments\":{\"path\":\".\",\"task\":\"MAVEN_TEST\"}}}".formatted(version.id(),codingProject())))
                .andExpect(request().asyncStarted()).andReturn();
        String approval=null;
        for(int attempt=0;attempt<1000 && approval==null;attempt++) {
            var ids=jdbc.query("SELECT a.id FROM approval_request a JOIN agent_run r ON r.id=a.run_id WHERE r.agent_version_id=:version AND a.status='PENDING'",java.util.Map.of("version",version.id()),(rs,row)->rs.getString(1));
            if(!ids.isEmpty())approval=ids.getFirst();else Thread.sleep(20);
        }
        assertThat(approval).isNotNull();mockMvc.perform(post("/api/approvals/{id}/reject",approval).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("REJECTED")));
        verify(modelGateway,never()).complete(any(),any(),any());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ModelProfileService modelProfiles;

    @Autowired
    private AgentService agents;

    @Autowired
    private KnowledgeService knowledge;

    @Autowired
    @Qualifier("primaryNamedParameterJdbcTemplate")
    private NamedParameterJdbcTemplate jdbc;

    @MockitoBean
    private StreamingModelGateway modelGateway;

    @MockitoBean
    private KnowledgeRetriever knowledgeRetriever;

    @MockitoBean
    private SshWorkspaceService sshWorkspaceService;

    @Test
    @SuppressWarnings("unchecked")
    void streamsRunDeltaAndDoneEvents() throws Exception {
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(2);
            consumer.accept("hello");
            consumer.accept(" world");
            return null;
        }).when(modelGateway).stream(any(), any(), any());
        when(knowledgeRetriever.retrieve(any(), any())).thenReturn(java.util.List.of(
                new RagSource("doc-1", "research.txt", 0, "verified source", 0.82)));

        var suffix = UUID.randomUUID().toString();
        var model = modelProfiles.create(new ModelProfileRequest(
                "chat-model-" + suffix, "OPENAI_COMPATIBLE", "https://example.com/v1",
                "test-model", "TEST_MODEL_KEY", new BigDecimal("0.5")));
        var knowledgeBase = knowledge.createBase(new KnowledgeBaseRequest("kb-" + suffix, "test"));
        var agent = agents.create(new AgentDefinitionRequest(
                "chat-agent-" + suffix, "test", model.id(), knowledgeBase.id(), "你是测试助手", List.of()));
        var version = agents.publish(agent.id());

        var body = """
                {"agentVersionId":"%s","message":"start test"}
                """.formatted(version.id());
        var result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(body))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:run")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:delta")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:sources")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("research.txt")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("hello")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")));
    }

    @Test
    void returnsDeterministicNoEvidenceAnswerWithoutCallingModel() throws Exception {
        when(knowledgeRetriever.retrieve(any(), any())).thenReturn(List.of());
        var suffix = UUID.randomUUID().toString();
        var model = modelProfiles.create(new ModelProfileRequest(
                "no-evidence-model-" + suffix, "OPENAI_COMPATIBLE", "https://example.com/v1",
                "test-model", "TEST_MODEL_KEY", new BigDecimal("0.2")));
        var knowledgeBase = knowledge.createBase(new KnowledgeBaseRequest("no-evidence-kb-" + suffix, "test"));
        var agent = agents.create(new AgentDefinitionRequest(
                "no-evidence-agent-" + suffix, "test", model.id(), knowledgeBase.id(), "只依据知识库回答", List.of()));
        var version = agents.publish(agent.id());

        var result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"agentVersionId":"%s","message":"知识库没有写过的事实是什么？"}
                                """.formatted(version.id())))
                .andExpect(request().asyncStarted()).andReturn();
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:evidence")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("INSUFFICIENT")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("EVIDENCE_CHECK")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("event:sources"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")));
        verify(modelGateway, never()).stream(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsTwoConcurrentConversationsIsolated() throws Exception {
        when(knowledgeRetriever.retrieve(any(), any())).thenReturn(List.of());
        var gate = new java.util.concurrent.CountDownLatch(2);
        doAnswer(invocation -> {
            java.util.List<com.agentstudio.model.ModelMessage> modelMessages = invocation.getArgument(1);
            Consumer<String> consumer = invocation.getArgument(2);
            var ownQuestion = modelMessages.getLast().content();
            gate.countDown();
            assertThat(gate.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            consumer.accept("answer-for:" + ownQuestion);
            return null;
        }).when(modelGateway).stream(any(), any(), any());
        var suffix = UUID.randomUUID().toString();
        var model = modelProfiles.create(new ModelProfileRequest(
                "parallel-model-" + suffix, "OPENAI_COMPATIBLE", "https://example.com/v1",
                "test-model", "TEST_MODEL_KEY", new BigDecimal("0.2")));
        var agent = agents.create(new AgentDefinitionRequest(
                "parallel-agent-" + suffix, "test", model.id(), null, "隔离测试", List.of()));
        var version = agents.publish(agent.id());

        var first = mockMvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"agentVersionId\":\"%s\",\"message\":\"alpha-only\"}".formatted(version.id())))
                .andExpect(request().asyncStarted()).andReturn();
        var second = mockMvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"agentVersionId\":\"%s\",\"message\":\"beta-only\"}".formatted(version.id())))
                .andExpect(request().asyncStarted()).andReturn();
        var firstBody = mockMvc.perform(asyncDispatch(first)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var secondBody = mockMvc.perform(asyncDispatch(second)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(firstBody).contains("answer-for:alpha-only").doesNotContain("beta-only");
        assertThat(secondBody).contains("answer-for:beta-only").doesNotContain("alpha-only");
        var pattern = java.util.regex.Pattern.compile("\\\"conversationId\\\":\\\"([^\\\"]+)\\\"");
        var firstId = pattern.matcher(firstBody); var secondId = pattern.matcher(secondBody);
        assertThat(firstId.find()).isTrue(); assertThat(secondId.find()).isTrue();
        assertThat(firstId.group(1)).isNotEqualTo(secondId.group(1));
    }

    @Test
    void executesBoundToolAndPersistsRunSteps() throws Exception {
        when(knowledgeRetriever.retrieve(any(), any())).thenReturn(List.of());
        when(modelGateway.complete(any(), any(), any()))
                .thenReturn(new ModelTurn("", List.of(new ModelToolCall(
                        "call-time-1", "current_time", "{\"zoneId\":\"Asia/Shanghai\"}"))))
                .thenReturn(new ModelTurn("tool answer ready", List.of()));

        var suffix = UUID.randomUUID().toString();
        var model = modelProfiles.create(new ModelProfileRequest(
                "tool-model-" + suffix, "OPENAI_COMPATIBLE", "https://example.com/v1",
                "test-model", "TEST_MODEL_KEY", new BigDecimal("0.2")));
        var agent = agents.create(new AgentDefinitionRequest(
                "tool-agent-" + suffix, "test", model.id(), null, "需要当前时间时必须调用工具。",
                List.of("current_time")));
        var version = agents.publish(agent.id());

        var result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"agentVersionId":"%s","message":"现在几点？"}
                                """.formatted(version.id())))
                .andExpect(request().asyncStarted()).andReturn();
        var response = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:step")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("current_time")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TOOL_RESULT")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("tool answer ready")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")))
                .andReturn().getResponse().getContentAsString();

        var matcher = java.util.regex.Pattern.compile("\\\"runId\\\":\\\"([^\\\"]+)\\\"").matcher(response);
        assertThat(matcher.find()).isTrue();
        mockMvc.perform(get("/api/runs/{id}", matcher.group(1)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("COMPLETED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("current_time")));
        mockMvc.perform(get("/api/audit-events").param("runId", matcher.group(1)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TOOL_REQUEST_VALIDATED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TOOL_EXECUTION_COMPLETED")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Asia/Shanghai"))));
    }

    @Test
    void executesCodingReadToolThroughGatewayWithRunAndAuditEvidence() throws Exception {
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/test-coding-workspace/src"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/test-coding-workspace/src/Example.java"),
                "class Example {}", java.nio.charset.StandardCharsets.UTF_8);
        when(knowledgeRetriever.retrieve(any(), any())).thenReturn(List.of());
        when(modelGateway.complete(any(), any(), any()))
                .thenReturn(new ModelTurn("", List.of(new ModelToolCall(
                        "call-coding-list-1", "list_workspace_directory", "{\"path\":\"src\"}"))))
                .thenReturn(new ModelTurn("workspace inspected", List.of()));

        var suffix = UUID.randomUUID().toString();
        var model = modelProfiles.create(new ModelProfileRequest(
                "coding-model-" + suffix, "OPENAI_COMPATIBLE", "https://example.com/v1",
                "test-model", "TEST_MODEL_KEY", new BigDecimal("0.2")));
        var agent = agents.create(new AgentDefinitionRequest(
                "coding-agent-" + suffix, "test", model.id(), null, "只读浏览代码工作区。",
                List.of("list_workspace_directory")));
        var version = agents.publish(agent.id());

        var result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"agentVersionId\":\"%s\",\"message\":\"浏览 src\",\"localProjectId\":\"%s\"}".formatted(version.id(),codingProject())))
                .andExpect(request().asyncStarted()).andReturn();
        var response = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("list_workspace_directory")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Example.java")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("workspace inspected")))
                .andReturn().getResponse().getContentAsString();
        var matcher = java.util.regex.Pattern.compile("\\\"runId\\\":\\\"([^\\\"]+)\\\"").matcher(response);
        assertThat(matcher.find()).isTrue();
        var runId = matcher.group(1);
        mockMvc.perform(get("/api/runs/{id}", runId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TOOL_RESULT")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Example.java")));
        mockMvc.perform(get("/api/audit-events").param("runId", runId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TOOL_REQUEST_VALIDATED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TOOL_EXECUTION_COMPLETED")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Example.java"))));
    }

    @Test
    void finalizesWithExistingResultsAtMaximumToolRounds() throws Exception {
        when(knowledgeRetriever.retrieve(any(), any())).thenReturn(List.of());
        var modelCalls = new java.util.concurrent.atomic.AtomicInteger();
        when(modelGateway.complete(any(), any(), any())).thenAnswer(invocation -> {
            if (modelCalls.incrementAndGet() <= 4) {
                return new ModelTurn("", List.of(new ModelToolCall(
                        "repeat-call-" + modelCalls.get(), "current_time", "{}")));
            }
            java.util.List<?> availableTools = invocation.getArgument(2);
            assertThat(availableTools).isEmpty();
            return new ModelTurn("finalized from existing tool results", List.of());
        });
        var suffix = UUID.randomUUID().toString();
        var model = modelProfiles.create(new ModelProfileRequest(
                "loop-model-" + suffix, "OPENAI_COMPATIBLE", "https://example.com/v1",
                "test-model", "TEST_MODEL_KEY", new BigDecimal("0.2")));
        var agent = agents.create(new AgentDefinitionRequest(
                "loop-agent-" + suffix, "test", model.id(), null, "持续请求工具。",
                List.of("current_time")));
        var version = agents.publish(agent.id());

        var result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"agentVersionId":"%s","message":"loop"}
                                """.formatted(version.id())))
                .andExpect(request().asyncStarted()).andReturn();
        var response = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("MODEL_FINALIZATION")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("finalized from existing tool results")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("达到最大工具轮数"))))
                .andReturn().getResponse().getContentAsString();
        assertThat(modelCalls).hasValue(5);
        var matcher = java.util.regex.Pattern.compile("\\\"runId\\\":\\\"([^\\\"]+)\\\"").matcher(response);
        assertThat(matcher.find()).isTrue();
        mockMvc.perform(get("/api/runs/{id}", matcher.group(1)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\":\"COMPLETED\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("MODEL_FINALIZATION")));
    }

    private com.agentstudio.agent.AgentVersion codingBudgetVersion() {
        var suffix=UUID.randomUUID().toString();
        var model=modelProfiles.create(new ModelProfileRequest("coding-budget-"+suffix,"OPENAI_COMPATIBLE","https://example.com/v1","test-model","TEST_MODEL_KEY",new BigDecimal("0.2")));
        return agents.publish(agents.create(new AgentDefinitionRequest("coding-budget-"+suffix,"test",model.id(),null,"使用真实工具完成任务。",List.of("current_time","apply_workspace_text_patch"))).id());
    }

    private String codingBudgetStream() throws Exception {
        var version=codingBudgetVersion();
        var result=mockMvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"agentVersionId\":\"%s\",\"message\":\"完成本地开发\",\"localProjectId\":\"%s\"}".formatted(version.id(),codingProject())))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(60000);
        return mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test void codingRunsContinueBeyondFourToolRounds() throws Exception {
        when(knowledgeRetriever.retrieve(any(),any())).thenReturn(List.of());
        var count=new java.util.concurrent.atomic.AtomicInteger();
        when(modelGateway.complete(any(),any(),any())).thenAnswer(invocation -> count.incrementAndGet()<=5
                ? new ModelTurn("",List.of(new ModelToolCall("coding-call-"+count.get(),"current_time","{}")))
                : new ModelTurn("工具检查结束",List.of()));
        assertThat(codingBudgetStream()).contains("event:done").doesNotContain("MODEL_FINALIZATION");
        assertThat(count).hasValue(6);
    }

    @Test void exhaustedCodingBudgetIsIncompleteAndDoesNotForceAFinalAnswer() throws Exception {
        when(knowledgeRetriever.retrieve(any(),any())).thenReturn(List.of());
        var count=new java.util.concurrent.atomic.AtomicInteger();
        when(modelGateway.complete(any(),any(),any())).thenAnswer(invocation -> new ModelTurn("",List.of(new ModelToolCall("budget-call-"+count.incrementAndGet(),"current_time","{}"))));
        var response=codingBudgetStream();
        assertThat(response).contains("TOOL_BUDGET_EXHAUSTED","任务未完成","event:error").doesNotContain("event:done","MODEL_FINALIZATION");
        assertThat(count).hasValue(6);
    }

    @Test void codingBatchAboveFourExecutesEveryCallInOrder() throws Exception {
        when(knowledgeRetriever.retrieve(any(),any())).thenReturn(List.of());
        var calls=java.util.stream.IntStream.rangeClosed(1,5)
                .mapToObj(i -> new ModelToolCall("batch-five-"+i,"current_time","{}" )).toList();
        when(modelGateway.complete(any(),any(),any()))
                .thenReturn(new ModelTurn("",calls)).thenReturn(new ModelTurn("检查完成",List.of()));
        var response=codingBudgetStream();
        assertThat(response).contains("event:done").doesNotContain("event:error","TOOL_BATCH_LIMIT");
        var results=java.util.regex.Pattern.compile("\"stepType\":\"TOOL_RESULT\".*?\"toolCallId\":\"(batch-five-\\d+)\"")
                .matcher(response).results().map(m -> m.group(1)).toList();
        assertThat(results).containsExactly("batch-five-1","batch-five-2","batch-five-3","batch-five-4","batch-five-5");
    }

    @Test void oversizedBatchIsNotExecutedAndModelCanResubmitSmallerBatch() throws Exception {
        when(knowledgeRetriever.retrieve(any(),any())).thenReturn(List.of());
        var calls=java.util.stream.IntStream.rangeClosed(1,17)
                .mapToObj(i -> new ModelToolCall("too-many-"+i,"apply_workspace_text_patch","{}" )).toList();
        var count=new java.util.concurrent.atomic.AtomicInteger();
        when(modelGateway.complete(any(),any(),any())).thenAnswer(invocation -> {
            int turn=count.incrementAndGet();
            if(turn==1)return new ModelTurn("",calls);
            if(turn==2) {
                List<com.agentstudio.model.ReActMessage> messages=invocation.getArgument(1);
                assertThat(messages.stream().filter(m -> "tool".equals(m.role())).toList())
                        .hasSize(17).allSatisfy(m -> assertThat(m.content()).contains("全部未执行","拆成小批"));
                return new ModelTurn("",List.of(new ModelToolCall("smaller-batch","current_time","{}")));
            }
            return new ModelTurn("检查完成",List.of());
        });
        var response=codingBudgetStream();
        assertThat(response).contains("TOOL_BATCH_LIMIT","smaller-batch","event:done")
                .doesNotContain("event:error","event:approval_required","\"toolName\":\"apply_workspace_text_patch\"");
        assertThat(count).hasValue(3);
    }

    @Test void repeatedOversizedBatchesStopWithoutExecutingOrClaimingCompletion() throws Exception {
        when(knowledgeRetriever.retrieve(any(),any())).thenReturn(List.of());
        var calls=java.util.stream.IntStream.rangeClosed(1,17)
                .mapToObj(i -> new ModelToolCall("repeated-big-"+i,"current_time","{}" )).toList();
        var count=new java.util.concurrent.atomic.AtomicInteger();
        when(modelGateway.complete(any(),any(),any())).thenAnswer(invocation -> {
            count.incrementAndGet();return new ModelTurn("",calls);
        });
        assertThat(codingBudgetStream()).contains("任务未完成","event:error","TOOL_BATCH_LIMIT")
                .doesNotContain("event:done","\"stepType\":\"TOOL_CALL\"","\"stepType\":\"TOOL_RESULT\"");
        assertThat(count).hasValue(3);
    }

    @Test void unexecutedOversizedBatchCannotBecomeSuccessfulModelAnswer() throws Exception {
        when(knowledgeRetriever.retrieve(any(),any())).thenReturn(List.of());
        var calls=java.util.stream.IntStream.rangeClosed(1,17)
                .mapToObj(i -> new ModelToolCall("not-executed-"+i,"current_time","{}" )).toList();
        when(modelGateway.complete(any(),any(),any())).thenReturn(new ModelTurn("",calls))
                .thenReturn(new ModelTurn("已完成全部修改",List.of()));
        assertThat(codingBudgetStream()).contains("任务未完成","event:error","TOOL_BATCH_LIMIT")
                .doesNotContain("event:done","已完成全部修改","\"stepType\":\"TOOL_RESULT\"");
    }

    @Test void nextRunRestoresFailedRunReceiptsInSameConversation()throws Exception {
        when(knowledgeRetriever.retrieve(any(),any())).thenReturn(List.of());
        var count=new java.util.concurrent.atomic.AtomicInteger();
        when(modelGateway.complete(any(),any(),any())).thenAnswer(invocation->new ModelTurn("",List.of(
                new ModelToolCall("resume-old-"+count.incrementAndGet(),"current_time","{}"))));
        var first=codingBudgetStream();
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var runData=java.util.regex.Pattern.compile("event:run\\s+data:([^\\r\\n]+)").matcher(first);
        assertThat(runData.find()).isTrue();var identity=json.readTree(runData.group(1));
        when(modelGateway.complete(any(),any(),any())).thenAnswer(invocation->{
            List<com.agentstudio.model.ReActMessage> messages=invocation.getArgument(1);
            assertThat(messages.stream().filter(m->"system".equals(m.role())).map(com.agentstudio.model.ReActMessage::content).toList().toString())
                    .contains(identity.path("runId").asText(),"current_time","新操作仍须重新审批");
            return new ModelTurn("已核对历史记录，等待继续剩余工作",List.of());
        });
        var result=mockMvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"agentVersionId\":\"%s\",\"conversationId\":\"%s\",\"message\":\"继续剩余工作\"}".formatted(
                        identity.path("agentVersionId").asText(),identity.path("conversationId").asText())))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(60000);
        var response=mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(response).contains("WORK_CONTEXT_RESTORED","event:done").doesNotContain("\"stepType\":\"TOOL_CALL\"","event:approval_required");
    }

    @Test void reconnectsOnlyModelRequestWithoutReplayingPreviousTool()throws Exception {
        when(knowledgeRetriever.retrieve(any(),any())).thenReturn(List.of());
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        when(modelGateway.complete(any(),any(),any())).thenAnswer(invocation->{
            int count=attempts.incrementAndGet();
            if(count==1)return new ModelTurn("",List.of(new ModelToolCall("before-connection-loss","current_time","{}")));
            if(count<=3)throw new java.net.http.HttpConnectTimeoutException("HTTP connect timed out");
            return new ModelTurn("已依据原工具结果完成检查",List.of());
        });
        var response=codingBudgetStream();
        assertThat(response).contains("MODEL_CONNECTION_RETRY","event:done").doesNotContain("event:error");
        assertThat(java.util.regex.Pattern.compile("\"stepType\":\"TOOL_RESULT\"").matcher(response).results().count()).isEqualTo(1);
        assertThat(attempts).hasValue(4);
    }
    @Test void exhaustedConnectionRetriesSaveHonestProgressAndCloseRun()throws Exception {
        when(knowledgeRetriever.retrieve(any(),any())).thenReturn(List.of());
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        when(modelGateway.complete(any(),any(),any())).thenAnswer(invocation->{attempts.incrementAndGet();throw new java.net.http.HttpConnectTimeoutException("connect");});
        var response=codingBudgetStream();
        assertThat(response).contains("已尝试 3 次","WORK_INTERRUPTED","本次实际进度","event:error").doesNotContain("event:done","\"stepType\":\"TOOL_CALL\"");
        assertThat(attempts).hasValue(3);
        var identity=java.util.regex.Pattern.compile("event:run\\s+data:([^\\r\\n]+)").matcher(response);assertThat(identity.find()).isTrue();
        var data=new com.fasterxml.jackson.databind.ObjectMapper().readTree(identity.group(1));
        var messages=jdbc.query("SELECT content FROM message WHERE conversation_id=:id AND role='assistant'",java.util.Map.of("id",data.path("conversationId").asText()),(rs,n)->rs.getString(1));
        assertThat(messages).hasSize(1);assertThat(messages.getFirst()).contains("本次实际进度","逐项审批");
    }
    @Test void responseTimeoutDoesNotAutomaticallyRepeatModelRequest()throws Exception {
        when(knowledgeRetriever.retrieve(any(),any())).thenReturn(List.of());
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        when(modelGateway.complete(any(),any(),any())).thenAnswer(invocation->{attempts.incrementAndGet();throw new java.net.http.HttpTimeoutException("response timed out");});
        assertThat(codingBudgetStream()).contains("event:error").doesNotContain("MODEL_CONNECTION_RETRY","event:done");
        assertThat(attempts).hasValue(1);
    }

    @Test void unsupportedSqlPathIsRejectedBeforeAskingForApproval()throws Exception {
        when(knowledgeRetriever.retrieve(any(),any())).thenReturn(List.of());
        var suffix=UUID.randomUUID().toString();
        var model=modelProfiles.create(new ModelProfileRequest("sql-boundary-"+suffix,"OPENAI_COMPATIBLE","https://example.com/v1","test","TEST_KEY",new BigDecimal("0.2")));
        var version=agents.publish(agents.create(new AgentDefinitionRequest("sql-boundary-"+suffix,"fixture",model.id(),null,"测试",List.of("create_workspace_text_file"))).id());
        when(modelGateway.complete(any(),any(),any())).thenReturn(new ModelTurn("",List.of(new ModelToolCall("invalid-sql-"+suffix,"create_workspace_text_file","{\"path\":\"src/run.sql\",\"content\":\"SELECT 1;\"}"))))
                .thenReturn(new ModelTurn("工具拒绝该路径，没有创建文件",List.of()));
        var result=mockMvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"agentVersionId\":\"%s\",\"localProjectId\":\"%s\",\"message\":\"验证路径边界\"}".formatted(version.id(),codingProject())))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(60000);
        var response=mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(response).contains("SQL 新建仅支持","工具拒绝该路径").doesNotContain("event:approval_required");
        assertThat(java.nio.file.Path.of("target/test-coding-workspace/src/run.sql")).doesNotExist();
    }

    @Test void textToolInstructionsNeverBecomeExecutedCallsOrSuccessfulFinalAnswers() throws Exception {
        when(knowledgeRetriever.retrieve(any(),any())).thenReturn(List.of());
        when(modelGateway.complete(any(),any(),any())).thenReturn(new ModelTurn("<｜｜DSML｜｜tool_calls><｜｜DSML｜｜invoke name=\"apply_workspace_text_patch\">bad</｜｜DSML｜｜invoke></｜｜DSML｜｜tool_calls>",List.of()));
        var response=codingBudgetStream();
        assertThat(response).contains("任务未完成","event:error").doesNotContain("event:done","TOOL_CALL","DSML");
    }

    @Test
    void highRiskToolWaitsForApprovalAndRejectionPreventsExecution() throws Exception {
        when(knowledgeRetriever.retrieve(any(), any())).thenReturn(List.of());
        var callId = "approval-" + UUID.randomUUID();
        when(modelGateway.complete(any(), any(), any()))
                .thenReturn(new ModelTurn("", List.of(new ModelToolCall(callId, "write_workspace_note",
                        "{\"fileName\":\"should-not-exist.md\",\"content\":\"blocked\"}"))))
                .thenReturn(new ModelTurn("write was rejected", List.of()));
        var suffix = UUID.randomUUID().toString();
        var model = modelProfiles.create(new ModelProfileRequest(
                "approval-model-" + suffix, "OPENAI_COMPATIBLE", "https://example.com/v1",
                "test-model", "TEST_MODEL_KEY", new BigDecimal("0.2")));
        var agent = agents.create(new AgentDefinitionRequest(
                "approval-agent-" + suffix, "test", model.id(), null, "写文件前请求审批。",
                List.of("write_workspace_note")));
        var version = agents.publish(agent.id());

        var result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"agentVersionId":"%s","message":"写一份笔记"}
                                """.formatted(version.id())))
                .andExpect(request().asyncStarted()).andReturn();

        String approvalId = null;
        for (int attempt = 0; attempt < 100 && approvalId == null; attempt++) {
            var ids = jdbc.query("SELECT id FROM approval_request WHERE tool_call_id=:callId",
                    java.util.Map.of("callId", callId), (rs, row) -> rs.getString("id"));
            if (!ids.isEmpty()) approvalId = ids.getFirst();
            else Thread.sleep(20);
        }
        assertThat(approvalId).isNotNull();
        mockMvc.perform(post("/api/approvals/{id}/reject", approvalId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test rejection\"}"))
                .andExpect(status().isOk());

        var response = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:approval_required")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("APPROVAL_RESULT")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("REJECTED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("write was rejected")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")))
                .andReturn().getResponse().getContentAsString();
        var runMatcher = java.util.regex.Pattern.compile("\\\"runId\\\":\\\"([^\\\"]+)\\\"").matcher(response);
        assertThat(runMatcher.find()).isTrue();
        mockMvc.perform(get("/api/audit-events").param("runId", runMatcher.group(1)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("APPROVAL_REQUIRED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("APPROVAL_DECIDED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TOOL_EXECUTION_SKIPPED")));
    }

    @Test
    void approvedCodingPatchExecutesThroughGatewayAndPersistsAuditEvidence() throws Exception {
        var suffix = UUID.randomUUID().toString();
        var relativePath = "src/Patch-" + suffix + ".txt";
        var file = java.nio.file.Path.of("target/test-coding-workspace").resolve(relativePath);
        java.nio.file.Files.createDirectories(file.getParent());
        var original = "before\nunchanged\n";
        java.nio.file.Files.writeString(file, original, java.nio.charset.StandardCharsets.UTF_8);
        var expectedSha256 = sha256(original.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var callId = "coding-patch-" + suffix;
        var arguments = """
                {"path":"%s","expectedSha256":"%s","replacements":[{"oldText":"before","newText":"after"}]}
                """.formatted(relativePath, expectedSha256).strip();

        when(knowledgeRetriever.retrieve(any(), any())).thenReturn(List.of());
        when(modelGateway.complete(any(), any(), any()))
                .thenReturn(new ModelTurn("", List.of(new ModelToolCall(
                        callId, "apply_workspace_text_patch", arguments))))
                .thenReturn(new ModelTurn("patch applied", List.of()));
        var model = modelProfiles.create(new ModelProfileRequest(
                "coding-patch-model-" + suffix, "OPENAI_COMPATIBLE", "https://example.com/v1",
                "test-model", "TEST_MODEL_KEY", new BigDecimal("0.2")));
        var agent = agents.create(new AgentDefinitionRequest(
                "coding-patch-agent-" + suffix, "test", model.id(), null,
                "读取摘要后，仅经审批应用精确文本补丁。", List.of("apply_workspace_text_patch")));
        var version = agents.publish(agent.id());

        var result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"agentVersionId\":\"%s\",\"message\":\"修改测试文件\",\"localProjectId\":\"%s\"}".formatted(version.id(),codingProject())))
                .andExpect(request().asyncStarted()).andReturn();

        String approvalId = null;
        for (int attempt = 0; attempt < 1000 && approvalId == null; attempt++) {
            var ids = jdbc.query("SELECT id FROM approval_request WHERE tool_call_id=:callId",
                    java.util.Map.of("callId", callId), (rs, row) -> rs.getString("id"));
            if (!ids.isEmpty()) approvalId = ids.getFirst();
            else Thread.sleep(20);
        }
        assertThat(approvalId).isNotNull();
        mockMvc.perform(post("/api/approvals/{id}/approve", approvalId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test approval\"}"))
                .andExpect(status().isOk());

        var response = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:approval_required")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("APPROVED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("apply_workspace_text_patch")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("patch applied")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")))
                .andReturn().getResponse().getContentAsString();
        assertThat(java.nio.file.Files.readString(file)).isEqualTo("after\nunchanged\n");
        var runMatcher = java.util.regex.Pattern.compile("\\\"runId\\\":\\\"([^\\\"]+)\\\"").matcher(response);
        assertThat(runMatcher.find()).isTrue();
        var runId = runMatcher.group(1);
        mockMvc.perform(get("/api/runs/{id}", runId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TOOL_RESULT")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("afterSha256")));
        mockMvc.perform(get("/api/audit-events").param("runId", runId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("APPROVAL_REQUIRED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("APPROVAL_DECIDED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TOOL_EXECUTION_COMPLETED")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(relativePath))));
    }

    @Test
    void workspaceVerificationRequiresApprovalAndRejectionPreventsProcessStart() throws Exception {
        when(knowledgeRetriever.retrieve(any(), any())).thenReturn(List.of());
        var suffix = UUID.randomUUID().toString();
        var callId = "verification-rejection-" + suffix;
        when(modelGateway.complete(any(), any(), any()))
                .thenReturn(new ModelTurn("", List.of(new ModelToolCall(callId,
                        "run_workspace_verification", "{\"path\":\".\",\"task\":\"NPM_BUILD\"}"))))
                .thenReturn(new ModelTurn("verification rejected", List.of()));
        var model = modelProfiles.create(new ModelProfileRequest(
                "verification-model-" + suffix, "OPENAI_COMPATIBLE", "https://example.com/v1",
                "test-model", "TEST_MODEL_KEY", new BigDecimal("0.2")));
        var agent = agents.create(new AgentDefinitionRequest(
                "verification-agent-" + suffix, "test", model.id(), null,
                "运行验证前请求审批。", List.of("run_workspace_verification")));
        var version = agents.publish(agent.id());

        var result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"agentVersionId\":\"%s\",\"message\":\"运行构建\",\"localProjectId\":\"%s\"}".formatted(version.id(),codingProject())))
                .andExpect(request().asyncStarted()).andReturn();

        String approvalId = null;
        for (int attempt = 0; attempt < 1000 && approvalId == null; attempt++) {
            var ids = jdbc.query("SELECT id FROM approval_request WHERE tool_call_id=:callId",
                    java.util.Map.of("callId", callId), (rs, row) -> rs.getString("id"));
            if (!ids.isEmpty()) approvalId = ids.getFirst();
            else Thread.sleep(20);
        }
        assertThat(approvalId).isNotNull();
        mockMvc.perform(post("/api/approvals/{id}/reject", approvalId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test rejection\"}"))
                .andExpect(status().isOk());

        var response = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("run_workspace_verification")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("REJECTED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("verification rejected")))
                .andReturn().getResponse().getContentAsString();
        var runMatcher = java.util.regex.Pattern.compile("\\\"runId\\\":\\\"([^\\\"]+)\\\"").matcher(response);
        assertThat(runMatcher.find()).isTrue();
        mockMvc.perform(get("/api/audit-events").param("runId", runMatcher.group(1)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("APPROVAL_REQUIRED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TOOL_EXECUTION_SKIPPED")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("NPM_BUILD"))));
    }

    @Test
    void remoteWorkspaceTaskRequiresBoundApprovalAndRejectionSkipsSshExecution() throws Exception {
        when(knowledgeRetriever.retrieve(any(), any())).thenReturn(List.of());
        var longRemoteRoot = "/srv/" + "approved-deployment-target/".repeat(8) + "project";
        when(sshWorkspaceService.current()).thenReturn(new SshWorkspaceProperties(
                "ssh.example.test", 22, "builder", longRemoteRoot, "SHA256:test-fingerprint-value",
                "TEST_SSH_PASSWORD", java.time.Duration.ofSeconds(5)));
        var suffix = UUID.randomUUID().toString();
        var callId = "remote-exec-rejection-" + suffix;
        when(modelGateway.complete(any(), any(), any()))
                .thenReturn(new ModelTurn("", List.of(new ModelToolCall(callId,
                        "run_remote_workspace_task", "{\"path\":\"app\",\"task\":\"GIT_STATUS\"}"))))
                .thenReturn(new ModelTurn("remote task rejected", List.of()));
        var model = modelProfiles.create(new ModelProfileRequest(
                "remote-exec-model-" + suffix, "OPENAI_COMPATIBLE", "https://example.com/v1",
                "test-model", "TEST_MODEL_KEY", new BigDecimal("0.2")));
        var agent = agents.create(new AgentDefinitionRequest(
                "remote-exec-agent-" + suffix, "test", model.id(), null,
                "远程任务执行前请求审批。", List.of("run_remote_workspace_task")));
        var version = agents.publish(agent.id());

        var result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"agentVersionId\":\"%s\",\"message\":\"查看远程状态\"}".formatted(version.id())))
                .andExpect(request().asyncStarted()).andReturn();

        String approvalId = null;
        for (int attempt = 0; attempt < 100 && approvalId == null; attempt++) {
            var ids = jdbc.query("SELECT id FROM approval_request WHERE tool_call_id=:callId",
                    java.util.Map.of("callId", callId), (rs, row) -> rs.getString("id"));
            if (!ids.isEmpty()) approvalId = ids.getFirst();
            else Thread.sleep(20);
        }
        assertThat(approvalId).isNotNull();
        var approval = jdbc.queryForMap("SELECT target_environment, arguments_json FROM approval_request WHERE id=:id",
                java.util.Map.of("id", approvalId));
        assertThat(approval.get("target_environment")).isEqualTo(
                "SSH:builder@ssh.example.test:22" + longRemoteRoot + "#SHA256:test-fingerprint-value");
        assertThat(approval.get("target_environment").toString()).hasSizeGreaterThan(160);
        assertThat(approval.get("arguments_json").toString()).contains("GIT_STATUS", "app");
        mockMvc.perform(post("/api/approvals/{id}/reject", approvalId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test rejection\"}"))
                .andExpect(status().isOk());

        var response = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("run_remote_workspace_task")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("REJECTED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("remote task rejected")))
                .andReturn().getResponse().getContentAsString();
        var runMatcher = java.util.regex.Pattern.compile("\\\"runId\\\":\\\"([^\\\"]+)\\\"").matcher(response);
        assertThat(runMatcher.find()).isTrue();
        mockMvc.perform(get("/api/audit-events").param("runId", runMatcher.group(1)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("APPROVAL_REQUIRED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TOOL_EXECUTION_SKIPPED")));
    }

    @Test
    void approvedRemoteTaskIsRejectedWhenSshTargetChangesBeforeExecution() throws Exception {
        when(knowledgeRetriever.retrieve(any(), any())).thenReturn(List.of());
        var approvedTarget = new SshWorkspaceProperties(
                "ssh.example.test", 22, "builder", "/srv/project", "SHA256:test-fingerprint-value",
                "TEST_SSH_PASSWORD", java.time.Duration.ofSeconds(5));
        var changedTarget = new SshWorkspaceProperties(
                "changed.example.test", 2222, "deployer", "/srv/changed", "SHA256:changed-fingerprint",
                "TEST_SSH_PASSWORD", java.time.Duration.ofSeconds(5));
        when(sshWorkspaceService.current()).thenReturn(approvedTarget, changedTarget, changedTarget);
        var suffix = UUID.randomUUID().toString();
        var callId = "remote-target-change-" + suffix;
        when(modelGateway.complete(any(), any(), any()))
                .thenReturn(new ModelTurn("", List.of(new ModelToolCall(callId,
                        "run_remote_workspace_task", "{\"path\":\"app\",\"task\":\"GIT_STATUS\"}"))))
                .thenReturn(new ModelTurn("target change blocked", List.of()));
        var model = modelProfiles.create(new ModelProfileRequest(
                "remote-target-model-" + suffix, "OPENAI_COMPATIBLE", "https://example.com/v1",
                "test-model", "TEST_MODEL_KEY", new BigDecimal("0.2")));
        var agent = agents.create(new AgentDefinitionRequest(
                "remote-target-agent-" + suffix, "test", model.id(), null,
                "远程任务执行前请求审批。", List.of("run_remote_workspace_task")));
        var version = agents.publish(agent.id());

        var result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"agentVersionId\":\"%s\",\"message\":\"查看远程状态\"}".formatted(version.id())))
                .andExpect(request().asyncStarted()).andReturn();

        String approvalId = null;
        for (int attempt = 0; attempt < 100 && approvalId == null; attempt++) {
            var ids = jdbc.query("SELECT id FROM approval_request WHERE tool_call_id=:callId",
                    java.util.Map.of("callId", callId), (rs, row) -> rs.getString("id"));
            if (!ids.isEmpty()) approvalId = ids.getFirst();
            else Thread.sleep(20);
        }
        assertThat(approvalId).isNotNull();
        mockMvc.perform(post("/api/approvals/{id}/approve", approvalId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test approval\"}"))
                .andExpect(status().isOk());

        var response = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("target change blocked")))
                .andReturn().getResponse().getContentAsString();
        var runMatcher = java.util.regex.Pattern.compile("\\\"runId\\\":\\\"([^\\\"]+)\\\"").matcher(response);
        assertThat(runMatcher.find()).isTrue();
        mockMvc.perform(get("/api/audit-events").param("runId", runMatcher.group(1)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TOOL_TARGET_CHANGED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("REJECTED")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("TOOL_EXECUTION_STARTED"))));
    }

    @Test
    void remoteWorkspaceTaskRejectsCommandLikeExtraParameterBeforeApproval() throws Exception {
        when(knowledgeRetriever.retrieve(any(), any())).thenReturn(List.of());
        var suffix = UUID.randomUUID().toString();
        var callId = "remote-exec-invalid-" + suffix;
        when(modelGateway.complete(any(), any(), any()))
                .thenReturn(new ModelTurn("", List.of(new ModelToolCall(callId,
                        "run_remote_workspace_task",
                        "{\"path\":\"app\",\"task\":\"GIT_STATUS\",\"command\":\"rm -rf /\"}"))))
                .thenReturn(new ModelTurn("dangerous parameter rejected", List.of()));
        var model = modelProfiles.create(new ModelProfileRequest(
                "remote-invalid-model-" + suffix, "OPENAI_COMPATIBLE", "https://example.com/v1",
                "test-model", "TEST_MODEL_KEY", new BigDecimal("0.2")));
        var agent = agents.create(new AgentDefinitionRequest(
                "remote-invalid-agent-" + suffix, "test", model.id(), null,
                "不得接受命令参数。", List.of("run_remote_workspace_task")));
        var version = agents.publish(agent.id());

        var result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"agentVersionId\":\"%s\",\"message\":\"运行危险命令\"}".formatted(version.id())))
                .andExpect(request().asyncStarted()).andReturn();
        var response = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("dangerous parameter rejected")))
                .andReturn().getResponse().getContentAsString();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_request WHERE tool_call_id=:callId",
                java.util.Map.of("callId", callId), Integer.class)).isZero();
        var runMatcher = java.util.regex.Pattern.compile("\\\"runId\\\":\\\"([^\\\"]+)\\\"").matcher(response);
        assertThat(runMatcher.find()).isTrue();
        mockMvc.perform(get("/api/audit-events").param("runId", runMatcher.group(1)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TOOL_REQUEST_REJECTED")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("rm -rf"))));
    }

    private String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

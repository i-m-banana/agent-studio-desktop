package com.agentstudio.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
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

@SpringBootTest
@AutoConfigureMockMvc
class ChatStreamIntegrationTests {

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
    void stopsAndMarksRunFailedAtMaximumToolRounds() throws Exception {
        when(knowledgeRetriever.retrieve(any(), any())).thenReturn(List.of());
        when(modelGateway.complete(any(), any(), any())).thenReturn(new ModelTurn("", List.of(
                new ModelToolCall("repeat-call", "current_time", "{}"))));
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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:error")))
                .andReturn().getResponse().getContentAsString();
        var matcher = java.util.regex.Pattern.compile("\\\"runId\\\":\\\"([^\\\"]+)\\\"").matcher(response);
        assertThat(matcher.find()).isTrue();
        mockMvc.perform(get("/api/runs/{id}", matcher.group(1)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\":\"FAILED\"")));
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
}

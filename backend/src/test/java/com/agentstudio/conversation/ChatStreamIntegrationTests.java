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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
}

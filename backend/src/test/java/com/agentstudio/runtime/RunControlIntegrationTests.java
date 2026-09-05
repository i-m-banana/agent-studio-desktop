package com.agentstudio.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.agentstudio.agent.AgentDefinitionRequest;
import com.agentstudio.agent.AgentService;
import com.agentstudio.knowledge.KnowledgeRetriever;
import com.agentstudio.model.ModelProfileRequest;
import com.agentstudio.model.ModelProfileService;
import com.agentstudio.model.StreamingModelGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "agent-studio.runtime.total-timeout=300ms")
@AutoConfigureMockMvc
class RunControlIntegrationTests {
    @Autowired MockMvc mockMvc;
    @Autowired ModelProfileService modelProfiles;
    @Autowired AgentService agents;
    @MockitoBean StreamingModelGateway modelGateway;
    @MockitoBean KnowledgeRetriever knowledgeRetriever;

    @BeforeEach
    void noKnowledgeSources() throws Exception {
        org.mockito.Mockito.when(knowledgeRetriever.retrieve(any(), any())).thenReturn(List.of());
    }

    @Test
    void cancelsActiveRunAndExposesItInHistory() throws Exception {
        var modelStarted = new CountDownLatch(1);
        blockStreamingModel(modelStarted);
        var versionId = publishedVersion("cancel");
        var result = start(versionId);
        assertThat(modelStarted.await(2, TimeUnit.SECONDS)).isTrue();
        var runId = waitForRun(versionId);

        mockMvc.perform(post("/api/runs/{id}/cancel", runId)).andExpect(status().isOk());
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:terminated")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("CANCELLED")));
        mockMvc.perform(get("/api/runs").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(runId)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("CANCELLED")));
        mockMvc.perform(get("/api/runs/{id}", runId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("RUN_TERMINATION")));
    }

    @Test
    void stopsRunAtConfiguredTotalTimeout() throws Exception {
        var modelStarted = new CountDownLatch(1);
        blockStreamingModel(modelStarted);
        var versionId = publishedVersion("timeout");
        var result = start(versionId);
        assertThat(modelStarted.await(2, TimeUnit.SECONDS)).isTrue();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:terminated")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TIMED_OUT")));
    }

    private void blockStreamingModel(CountDownLatch started) throws Exception {
        doAnswer(invocation -> {
            started.countDown();
            Thread.sleep(5_000);
            return null;
        }).when(modelGateway).stream(any(), any(), any());
    }

    private String publishedVersion(String prefix) {
        var suffix = UUID.randomUUID().toString();
        var model = modelProfiles.create(new ModelProfileRequest(prefix + "-model-" + suffix,
                "OPENAI_COMPATIBLE", "https://example.com/v1", "test-model", "TEST_MODEL_KEY",
                new BigDecimal("0.2")));
        var agent = agents.create(new AgentDefinitionRequest(prefix + "-agent-" + suffix,
                "test", model.id(), null, "测试运行控制", List.of()));
        return agents.publish(agent.id()).id();
    }

    private org.springframework.test.web.servlet.MvcResult start(String versionId) throws Exception {
        return mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"agentVersionId\":\"" + versionId + "\",\"message\":\"start\"}"))
                .andExpect(request().asyncStarted()).andReturn();
    }

    private String waitForRun(String versionId) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            var matches = mockMvc.perform(get("/api/runs").param("limit", "100"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var matcher = java.util.regex.Pattern.compile(
                    "\\\"id\\\":\\\"([^\\\"]+)\\\",\\\"conversationId\\\":\\\"[^\\\"]+\\\",\\\"agentVersionId\\\":\\\""
                            + java.util.regex.Pattern.quote(versionId) + "\\\"").matcher(matches);
            if (matcher.find()) return matcher.group(1);
            Thread.sleep(10);
        }
        throw new AssertionError("run was not created");
    }
}

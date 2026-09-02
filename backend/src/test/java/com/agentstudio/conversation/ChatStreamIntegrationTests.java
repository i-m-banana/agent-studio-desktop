package com.agentstudio.conversation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Consumer;

import com.agentstudio.agent.AgentDefinitionRequest;
import com.agentstudio.agent.AgentService;
import com.agentstudio.model.ModelProfileRequest;
import com.agentstudio.model.ModelProfileService;
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

    @MockitoBean
    private StreamingModelGateway modelGateway;

    @Test
    @SuppressWarnings("unchecked")
    void streamsRunDeltaAndDoneEvents() throws Exception {
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(2);
            consumer.accept("hello");
            consumer.accept(" world");
            return null;
        }).when(modelGateway).stream(any(), any(), any());

        var suffix = UUID.randomUUID().toString();
        var model = modelProfiles.create(new ModelProfileRequest(
                "chat-model-" + suffix, "OPENAI_COMPATIBLE", "https://example.com/v1",
                "test-model", "TEST_MODEL_KEY", new BigDecimal("0.5")));
        var agent = agents.create(new AgentDefinitionRequest(
                "chat-agent-" + suffix, "test", model.id(), "你是测试助手"));
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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("hello")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")));
    }
}

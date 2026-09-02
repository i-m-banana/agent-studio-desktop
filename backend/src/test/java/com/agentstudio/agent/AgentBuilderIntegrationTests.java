package com.agentstudio.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import com.agentstudio.model.ModelProfileRequest;
import com.agentstudio.model.ModelProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AgentBuilderIntegrationTests {

    @Autowired
    private ModelProfileService modelProfiles;

    @Autowired
    private AgentService agents;

    @Test
    void publishedVersionsRemainImmutableWhenDraftAndModelChange() {
        var suffix = UUID.randomUUID().toString();
        var model = modelProfiles.create(new ModelProfileRequest(
                "model-" + suffix, "OPENAI_COMPATIBLE", "https://example.com/v1",
                "model-a", "TEST_MODEL_KEY", new BigDecimal("0.4")));
        var agent = agents.create(new AgentDefinitionRequest(
                "agent-" + suffix, "test", model.id(), "prompt-v1"));

        var versionOne = agents.publish(agent.id());

        modelProfiles.update(model.id(), new ModelProfileRequest(
                model.name(), model.provider(), model.baseUrl(), "model-b",
                model.apiKeyEnv(), new BigDecimal("0.8")));
        agents.update(agent.id(), new AgentDefinitionRequest(
                agent.name(), agent.description(), model.id(), "prompt-v2"));
        var versionTwo = agents.publish(agent.id());

        assertThat(versionOne.versionNumber()).isEqualTo(1);
        assertThat(versionOne.modelName()).isEqualTo("model-a");
        assertThat(versionOne.systemPrompt()).isEqualTo("prompt-v1");
        assertThat(versionTwo.versionNumber()).isEqualTo(2);
        assertThat(versionTwo.modelName()).isEqualTo("model-b");
        assertThat(versionTwo.systemPrompt()).isEqualTo("prompt-v2");
        assertThat(agents.versions(agent.id())).extracting(AgentVersion::versionNumber)
                .containsExactly(2, 1);
    }
}


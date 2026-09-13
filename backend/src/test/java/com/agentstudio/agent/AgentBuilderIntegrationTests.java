package com.agentstudio.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;

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
                "agent-" + suffix, "test", model.id(), "", "prompt-v1", List.of("current_time")));

        assertThat(agent.draftKnowledgeBaseId()).isNull();

        assertThatThrownBy(() -> agents.create(new AgentDefinitionRequest(
                agent.name(), "duplicate", model.id(), null, "prompt", List.of())))
                .hasMessage("Agent 名称已存在，请换一个名称或编辑已有 Agent");

        var versionOne = agents.publish(agent.id());

        modelProfiles.update(model.id(), new ModelProfileRequest(
                model.name(), model.provider(), model.baseUrl(), "model-b",
                model.apiKeyEnv(), new BigDecimal("0.8")));
        agents.update(agent.id(), new AgentDefinitionRequest(
                agent.name(), agent.description(), model.id(), null, "prompt-v2", List.of()));
        var versionTwo = agents.publish(agent.id());

        assertThat(versionOne.versionNumber()).isEqualTo(1);
        assertThat(versionOne.modelName()).isEqualTo("model-a");
        assertThat(versionOne.systemPrompt()).isEqualTo("prompt-v1");
        assertThat(versionOne.toolNames()).containsExactly("current_time");
        assertThat(versionTwo.versionNumber()).isEqualTo(2);
        assertThat(versionTwo.modelName()).isEqualTo("model-b");
        assertThat(versionTwo.systemPrompt()).isEqualTo("prompt-v2");
        assertThat(versionTwo.toolNames()).isEmpty();
        assertThat(agents.versions(agent.id())).extracting(AgentVersion::versionNumber)
                .containsExactly(2, 1);

        var archived = agents.archiveVersion(agent.id(), versionOne.id());
        assertThat(archived.archived()).isTrue();
        assertThat(agents.versions(agent.id())).extracting(AgentVersion::versionNumber).containsExactly(2);
        assertThat(agents.versions(agent.id(), true)).hasSize(2);
        agents.deleteVersion(agent.id(), versionOne.id());
        assertThat(agents.versions(agent.id(), true)).extracting(AgentVersion::versionNumber).containsExactly(2);
        assertThatThrownBy(() -> agents.archiveVersion(agent.id(), versionTwo.id()))
                .hasMessage("最新版本不能归档；请先发布替代版本");
    }
}

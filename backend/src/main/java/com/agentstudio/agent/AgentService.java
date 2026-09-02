package com.agentstudio.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.agentstudio.model.ModelProfileService;
import com.agentstudio.knowledge.KnowledgeService;
import com.agentstudio.system.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentService {

    private final AgentRepository repository;
    private final ModelProfileService modelProfiles;
    private final KnowledgeService knowledge;

    public AgentService(AgentRepository repository, ModelProfileService modelProfiles,
                        KnowledgeService knowledge) {
        this.repository = repository;
        this.modelProfiles = modelProfiles;
        this.knowledge = knowledge;
    }

    public List<AgentDefinition> list() {
        return repository.findAll();
    }

    public AgentDefinition get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Agent 不存在"));
    }

    public AgentVersion getVersion(String id) {
        return repository.findVersion(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Agent 版本不存在"));
    }

    public AgentDefinition create(AgentDefinitionRequest request) {
        modelProfiles.get(request.modelProfileId());
        validateKnowledgeBase(request.knowledgeBaseId());
        var now = Instant.now();
        var definition = new AgentDefinition(UUID.randomUUID().toString(), request.name().trim(),
                text(request.description()), request.modelProfileId(), request.knowledgeBaseId(), request.systemPrompt().trim(),
                0, now, now);
        repository.insert(definition);
        return definition;
    }

    public AgentDefinition update(String id, AgentDefinitionRequest request) {
        var existing = get(id);
        modelProfiles.get(request.modelProfileId());
        validateKnowledgeBase(request.knowledgeBaseId());
        var updated = new AgentDefinition(existing.id(), request.name().trim(), text(request.description()),
                request.modelProfileId(), request.knowledgeBaseId(), request.systemPrompt().trim(), existing.latestVersionNumber(),
                existing.createdAt(), Instant.now());
        repository.updateDraft(updated);
        return updated;
    }

    @Transactional
    public AgentVersion publish(String id) {
        var definition = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Agent 不存在"));
        var model = modelProfiles.get(definition.draftModelProfileId());
        var now = Instant.now();
        var version = new AgentVersion(UUID.randomUUID().toString(), definition.id(),
                definition.latestVersionNumber() + 1, definition.draftKnowledgeBaseId(), model.id(), model.name(), model.provider(),
                model.baseUrl(), model.modelName(), model.apiKeyEnv(), model.temperature(),
                definition.draftSystemPrompt(), now);
        repository.insertVersionAndAdvance(version, now);
        return version;
    }

    public List<AgentVersion> versions(String id) {
        get(id);
        return repository.findVersions(id);
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private void validateKnowledgeBase(String knowledgeBaseId) {
        if (knowledgeBaseId != null && !knowledgeBaseId.isBlank()) {
            knowledge.getBase(knowledgeBaseId);
        }
    }
}

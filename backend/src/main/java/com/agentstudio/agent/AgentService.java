package com.agentstudio.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.agentstudio.model.ModelProfileService;
import com.agentstudio.knowledge.KnowledgeService;
import com.agentstudio.system.ApiException;
import com.agentstudio.tool.ToolRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentService {

    private final AgentRepository repository;
    private final ModelProfileService modelProfiles;
    private final KnowledgeService knowledge;
    private final ToolRegistry tools;

    public AgentService(AgentRepository repository, ModelProfileService modelProfiles,
                        KnowledgeService knowledge, ToolRegistry tools) {
        this.repository = repository;
        this.modelProfiles = modelProfiles;
        this.knowledge = knowledge;
        this.tools = tools;
    }

    public List<AgentDefinition> list() {
        return repository.findAll().stream().map(this::withDraftTools).toList();
    }

    public AgentDefinition get(String id) {
        return repository.findById(id).map(this::withDraftTools)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Agent 不存在"));
    }

    public AgentVersion getVersion(String id) {
        return repository.findVersion(id).map(this::withVersionTools)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Agent 版本不存在"));
    }

    @Transactional
    public AgentDefinition create(AgentDefinitionRequest request) {
        var name = request.name().trim();
        validateUniqueName(name, null);
        modelProfiles.get(request.modelProfileId());
        var knowledgeBaseId = normalizedOptionalId(request.knowledgeBaseId());
        validateKnowledgeBase(knowledgeBaseId);
        var toolNames = normalizedTools(request.toolNames());
        var now = Instant.now();
        var definition = new AgentDefinition(UUID.randomUUID().toString(), name,
                text(request.description()), request.modelProfileId(), knowledgeBaseId, request.systemPrompt().trim(),
                toolNames, 0, now, now);
        repository.insert(definition);
        repository.replaceDraftTools(definition.id(), toolNames);
        return definition;
    }

    @Transactional
    public AgentDefinition update(String id, AgentDefinitionRequest request) {
        var existing = get(id);
        var name = request.name().trim();
        validateUniqueName(name, id);
        modelProfiles.get(request.modelProfileId());
        var knowledgeBaseId = normalizedOptionalId(request.knowledgeBaseId());
        validateKnowledgeBase(knowledgeBaseId);
        var toolNames = normalizedTools(request.toolNames());
        var updated = new AgentDefinition(existing.id(), name, text(request.description()),
                request.modelProfileId(), knowledgeBaseId, request.systemPrompt().trim(), toolNames,
                existing.latestVersionNumber(),
                existing.createdAt(), Instant.now());
        repository.updateDraft(updated);
        repository.replaceDraftTools(updated.id(), toolNames);
        return updated;
    }

    @Transactional
    public AgentVersion publish(String id) {
        var definition = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Agent 不存在"));
        var model = modelProfiles.get(definition.draftModelProfileId());
        var toolNames = repository.findDraftTools(definition.id());
        var now = Instant.now();
        var version = new AgentVersion(UUID.randomUUID().toString(), definition.id(),
                definition.latestVersionNumber() + 1, definition.draftKnowledgeBaseId(), model.id(), model.name(), model.provider(),
                model.baseUrl(), model.modelName(), model.apiKeyEnv(), model.temperature(),
                definition.draftSystemPrompt(), toolNames, now);
        repository.insertVersionAndAdvance(version, now);
        repository.snapshotVersionTools(version.id(), toolNames);
        return version;
    }

    public List<AgentVersion> versions(String id) {
        get(id);
        return repository.findVersions(id).stream().map(this::withVersionTools).toList();
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private void validateKnowledgeBase(String knowledgeBaseId) {
        if (knowledgeBaseId != null) {
            knowledge.getBase(knowledgeBaseId);
        }
    }

    private String normalizedOptionalId(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private List<String> normalizedTools(List<String> requested) {
        var names = requested == null ? List.<String>of() : requested.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim).distinct().sorted().toList();
        tools.validateNames(names);
        return names;
    }

    private void validateUniqueName(String name, String excludedId) {
        if (repository.existsByName(name, excludedId)) {
            throw new ApiException(HttpStatus.CONFLICT, "Agent 名称已存在，请换一个名称或编辑已有 Agent");
        }
    }

    private AgentDefinition withDraftTools(AgentDefinition definition) {
        return new AgentDefinition(definition.id(), definition.name(), definition.description(),
                definition.draftModelProfileId(), definition.draftKnowledgeBaseId(), definition.draftSystemPrompt(),
                repository.findDraftTools(definition.id()), definition.latestVersionNumber(),
                definition.createdAt(), definition.updatedAt());
    }

    private AgentVersion withVersionTools(AgentVersion version) {
        return new AgentVersion(version.id(), version.agentDefinitionId(), version.versionNumber(),
                version.knowledgeBaseId(), version.modelProfileId(), version.modelProfileName(), version.provider(),
                version.baseUrl(), version.modelName(), version.apiKeyEnv(), version.temperature(),
                version.systemPrompt(), repository.findVersionTools(version.id()), version.publishedAt());
    }
}

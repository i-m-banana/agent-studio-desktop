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
        return list(false);
    }

    public List<AgentDefinition> list(boolean includeArchived) {
        return repository.findAll().stream().filter(a -> includeArchived || a.archivedAt() == null).map(this::withDraftTools).toList();
    }

    @Transactional
    public AgentDefinition archive(String id, boolean archived) {
        var definition = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,"助手不存在"));
        if ((definition.archivedAt() != null) != archived) repository.setArchived(id,archived);
        return get(id);
    }

    private void requireActive(AgentDefinition definition) {
        if (definition.archivedAt() != null) throw new ApiException(HttpStatus.CONFLICT,"助手已归档，请先恢复再编辑或发布");
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
        requireActive(existing);
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
        requireActive(definition);
        var toolNames = repository.findDraftTools(definition.id());
        var now = Instant.now();
        var version = new AgentVersion(UUID.randomUUID().toString(), definition.id(),
                definition.latestVersionNumber() + 1, definition.draftKnowledgeBaseId(), model.id(), model.name(), model.provider(),
                model.baseUrl(), model.modelName(), model.apiKeyEnv(), model.temperature(),
                definition.draftSystemPrompt(), toolNames, now, null, 0);
        repository.insertVersionAndAdvance(version, now);
        repository.snapshotVersionTools(version.id(), toolNames);
        return version;
    }

    public List<AgentVersion> versions(String id) { return versions(id, false); }

    public List<AgentVersion> versions(String id, boolean includeArchived) {
        get(id);
        return repository.findVersions(id, includeArchived).stream().map(this::withVersionTools).toList();
    }

    @Transactional
    public AgentVersion archiveVersion(String definitionId, String versionId) {
        var definition = get(definitionId);
        var version = ownedVersion(definitionId, versionId);
        if (version.versionNumber() == definition.latestVersionNumber()) {
            throw new ApiException(HttpStatus.CONFLICT, "最新版本不能归档；请先发布替代版本");
        }
        if (!version.archived()) repository.archiveVersion(versionId, Instant.now());
        return getVersion(versionId);
    }

    @Transactional
    public AgentVersion restoreVersion(String definitionId, String versionId) {
        var version = ownedVersion(definitionId, versionId);
        if (version.archived()) repository.restoreVersion(versionId);
        return getVersion(versionId);
    }

    @Transactional
    public void deleteVersion(String definitionId, String versionId) {
        var definition = get(definitionId);
        var version = ownedVersion(definitionId, versionId);
        if (version.versionNumber() == definition.latestVersionNumber()) {
            throw new ApiException(HttpStatus.CONFLICT, "最新版本不能删除；请先发布替代版本");
        }
        if (!version.archived()) {
            throw new ApiException(HttpStatus.CONFLICT, "请先归档该版本，再执行永久删除");
        }
        var usage = repository.countVersionUsage(versionId);
        if (usage > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "该版本已有会话或运行记录，只能归档，不能永久删除");
        }
        repository.deleteVersion(versionId);
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
                definition.createdAt(), definition.updatedAt(), definition.archivedAt());
    }

    private AgentVersion withVersionTools(AgentVersion version) {
        return new AgentVersion(version.id(), version.agentDefinitionId(), version.versionNumber(),
                version.knowledgeBaseId(), version.modelProfileId(), version.modelProfileName(), version.provider(),
                version.baseUrl(), version.modelName(), version.apiKeyEnv(), version.temperature(),
                version.systemPrompt(), repository.findVersionTools(version.id()), version.publishedAt(),
                version.archivedAt(), repository.countVersionUsage(version.id()));
    }

    private AgentVersion ownedVersion(String definitionId, String versionId) {
        var version = getVersion(versionId);
        if (!version.agentDefinitionId().equals(definitionId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Agent 版本不存在");
        }
        return version;
    }
}

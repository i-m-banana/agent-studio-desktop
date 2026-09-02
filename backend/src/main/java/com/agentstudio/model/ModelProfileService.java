package com.agentstudio.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.agentstudio.system.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ModelProfileService {

    private final ModelProfileRepository repository;

    public ModelProfileService(ModelProfileRepository repository) {
        this.repository = repository;
    }

    public List<ModelProfile> list() {
        return repository.findAll();
    }

    public ModelProfile get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "模型配置不存在"));
    }

    public ModelProfile create(ModelProfileRequest request) {
        var now = Instant.now();
        var profile = new ModelProfile(UUID.randomUUID().toString(), request.name().trim(),
                request.provider().trim(), trimSlash(request.baseUrl()), request.modelName().trim(),
                request.apiKeyEnv().trim(), request.effectiveTemperature(), now, now);
        repository.insert(profile);
        return profile;
    }

    public ModelProfile update(String id, ModelProfileRequest request) {
        var existing = get(id);
        var profile = new ModelProfile(existing.id(), request.name().trim(), request.provider().trim(),
                trimSlash(request.baseUrl()), request.modelName().trim(), request.apiKeyEnv().trim(),
                request.effectiveTemperature(), existing.createdAt(), Instant.now());
        repository.update(profile);
        return profile;
    }

    private String trimSlash(String value) {
        return value.trim().replaceAll("/+$", "");
    }
}


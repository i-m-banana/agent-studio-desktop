package com.agentstudio.secret;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import com.agentstudio.adapter.mcp.McpRepository;
import com.agentstudio.model.ModelProfileRepository;
import com.agentstudio.system.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SecretService {
    private static final String NAME_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";
    private final SecretStore store;
    private final SecretResolver resolver;
    private final ModelProfileRepository models;
    private final McpRepository mcp;

    public SecretService(SecretStore store, SecretResolver resolver, ModelProfileRepository models, McpRepository mcp) {
        this.store = store; this.resolver = resolver; this.models = models; this.mcp = mcp;
    }

    public List<SecretStatus> list() {
        var references = new LinkedHashMap<String, List<String>>();
        models.findAll().forEach(model -> add(references, model.apiKeyEnv(), "模型：" + model.name()));
        mcp.findServers().forEach(server -> {
            add(references, server.apiKeyEnv(), "MCP：" + server.name());
            server.environment().values().forEach(name -> add(references, name, "MCP 子进程：" + server.name()));
        });
        return references.entrySet().stream().map(entry -> status(entry.getKey(), entry.getValue())).toList();
    }

    public SecretStatus put(String name, String value) {
        validate(name);
        store.write(name, value);
        return status(name, usages(name));
    }

    public void delete(String name) {
        validate(name);
        if (System.getenv(name) != null && !System.getenv(name).isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "当前进程环境变量正在覆盖该凭据，无法从页面删除；请关闭程序后清除环境变量");
        }
        store.delete(name);
    }

    private SecretStatus status(String name, List<String> usedBy) {
        var source = resolver.source(name);
        return new SecretStatus(name, !"NONE".equals(source), source, List.copyOf(usedBy));
    }

    private List<String> usages(String name) {
        return list().stream().filter(item -> item.name().equals(name)).findFirst()
                .map(SecretStatus::usedBy).orElse(List.of());
    }

    private void add(LinkedHashMap<String, List<String>> references, String name, String usage) {
        if (name == null || name.isBlank()) return;
        references.computeIfAbsent(name, ignored -> new ArrayList<>()).add(usage);
    }

    private void validate(String name) {
        if (name == null || name.length() > 160 || !name.matches(NAME_PATTERN)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "凭据名称必须是合法的环境变量名");
        }
    }
}

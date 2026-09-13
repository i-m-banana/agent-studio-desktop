package com.agentstudio.system;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.agentstudio.adapter.mcp.McpRepository;
import com.agentstudio.knowledge.EmbeddingGateway;
import com.agentstudio.model.ModelProfileRepository;
import com.agentstudio.secret.SecretResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SystemReadinessService {

    private final JdbcTemplate primaryJdbc;
    private final ObjectProvider<JdbcTemplate> vectorJdbc;
    private final EmbeddingGateway embedding;
    private final ModelProfileRepository models;
    private final McpRepository mcp;
    private final SecretResolver secrets;
    private final Path dataRoot;
    private final String version;

    public SystemReadinessService(
            @Qualifier("primaryJdbcTemplate") JdbcTemplate primaryJdbc,
            @Qualifier("vectorJdbcTemplate") ObjectProvider<JdbcTemplate> vectorJdbc,
            EmbeddingGateway embedding,
            ModelProfileRepository models,
            McpRepository mcp,
            SecretResolver secrets,
            @Value("${agent-studio.data-dir:../data}") String dataDir,
            @Value("${agent-studio.version:development}") String version) {
        this.primaryJdbc = primaryJdbc;
        this.vectorJdbc = vectorJdbc;
        this.embedding = embedding;
        this.models = models;
        this.mcp = mcp;
        this.secrets = secrets;
        this.dataRoot = Path.of(dataDir).toAbsolutePath().normalize();
        this.version = version;
    }

    public SystemReadiness inspect() {
        var checks = new ArrayList<ReadinessCheck>();
        checks.add(databaseCheck());
        checks.add(vectorCheck());
        checks.add(embeddingCheck());
        checks.add(modelKeysCheck());
        checks.add(dataDirectoryCheck());
        checks.add(mcpCheck());
        var status = checks.stream().anyMatch(check -> check.required() && "FAILED".equals(check.status()))
                ? "NOT_READY"
                : checks.stream().anyMatch(check -> !"READY".equals(check.status())) ? "DEGRADED" : "READY";
        return new SystemReadiness("agent-studio-backend", version, status, java.time.Instant.now(), List.copyOf(checks));
    }

    private ReadinessCheck databaseCheck() {
        try {
            primaryJdbc.queryForObject("SELECT 1", Integer.class);
            return ready("mysql", "业务数据库", "MySQL 连接正常，迁移后的业务数据可读写。", true);
        } catch (Exception exception) {
            return failed("mysql", "业务数据库", safe(exception), "确认 Docker 中 MySQL 健康且 MYSQL_* 配置正确。", true);
        }
    }

    private ReadinessCheck vectorCheck() {
        var jdbc = vectorJdbc.getIfAvailable();
        if (jdbc == null) {
            return warning("pgvector", "向量数据库", "向量数据库已停用。", "正式使用 RAG 前启用 agent-studio.vector.enabled。", false);
        }
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ready("pgvector", "向量数据库", "pgvector 连接正常。", true);
        } catch (Exception exception) {
            return failed("pgvector", "向量数据库", safe(exception), "确认 Docker 中 pgvector 健康且 POSTGRES_* 配置正确。", true);
        }
    }

    private ReadinessCheck embeddingCheck() {
        try {
            var vector = embedding.embedQuery("Agent Studio readiness probe");
            if (vector.length != embedding.dimensions()) {
                throw new IllegalStateException("返回维度 " + vector.length + "，预期 " + embedding.dimensions());
            }
            return ready("embedding", "Embedding 模型",
                    embedding.modelName() + " · " + embedding.dimensions() + " 维 · " + embedding.indexVersion(), true);
        } catch (Exception exception) {
            return failed("embedding", "Embedding 模型", safe(exception),
                    "启动 Ollama，并确认 EMBEDDING_MODEL 已下载且维度配置一致。", true);
        }
    }

    private ReadinessCheck modelKeysCheck() {
        try {
            var profiles = models.findAll();
            if (profiles.isEmpty()) {
                return warning("model-keys", "对话模型密钥", "尚未创建模型配置。", "先在“模型配置”页面新增模型。", false);
            }
            var missingProfiles = profiles.stream().filter(profile -> profile.apiKeyEnv() == null
                            || profile.apiKeyEnv().isBlank() || secrets.resolve(profile.apiKeyEnv()).isEmpty())
                    .toList();
            var missing = missingProfiles.stream().limit(5)
                    .map(profile -> profile.name() + "（" + profile.apiKeyEnv() + "）")
                    .collect(Collectors.joining("、"));
            if (missingProfiles.size() > 5) missing += "，另有 " + (missingProfiles.size() - 5) + " 个";
            if (!missingProfiles.isEmpty()) {
                return failed("model-keys", "对话模型密钥", "未设置：" + missing,
                        "到“模型配置”的安全凭据区保存密钥，或在启动后端前设置环境变量。", true);
            }
            return ready("model-keys", "对话模型密钥", profiles.size() + " 个模型配置的凭据均已配置。", true);
        } catch (Exception exception) {
            return failed("model-keys", "对话模型密钥", safe(exception), "先恢复业务数据库连接。", true);
        }
    }

    private ReadinessCheck dataDirectoryCheck() {
        try {
            Files.createDirectories(dataRoot);
            if (!Files.isWritable(dataRoot)) throw new IllegalStateException("目录不可写");
            return ready("data-dir", "本地数据目录", dataRoot.toString(), true);
        } catch (Exception exception) {
            return failed("data-dir", "本地数据目录", dataRoot + " · " + safe(exception),
                    "检查 AGENT_STUDIO_DATA_DIR 路径及当前用户的写入权限。", true);
        }
    }

    private ReadinessCheck mcpCheck() {
        try {
            var enabled = mcp.findServers().stream().filter(server -> server.enabled()).toList();
            if (enabled.isEmpty()) return warning("mcp", "MCP 连接", "没有启用的 MCP Server。", "不使用 MCP 时可忽略。", false);
            var missingEnvironment = enabled.stream().flatMap(server -> {
                        var names = new java.util.ArrayList<String>();
                        if (!blank(server.apiKeyEnv()) && secrets.resolve(server.apiKeyEnv()).isEmpty()) names.add(server.apiKeyEnv());
                        server.environment().values().stream().filter(name -> secrets.resolve(name).isEmpty()).forEach(names::add);
                        return names.stream().map(name -> server.name() + "（" + name + "）");
                    }).distinct().limit(5).collect(Collectors.joining("、"));
            if (!missingEnvironment.isBlank()) return warning("mcp", "MCP 连接", "缺少凭据：" + missingEnvironment,
                    "到“模型配置”的安全凭据区保存，或设置环境变量，再重新同步对应 MCP Server。", false);
            var failed = enabled.stream().filter(server -> !"READY".equals(server.status()))
                    .map(server -> server.name() + "（" + server.status() + "）").collect(Collectors.joining("、"));
            if (!failed.isBlank()) return warning("mcp", "MCP 连接", "未就绪：" + failed, "到 MCP 页面重新测试并同步。", false);
            return ready("mcp", "MCP 连接", enabled.size() + " 个已启用 Server 均已同步。", false);
        } catch (Exception exception) {
            return warning("mcp", "MCP 连接", safe(exception), "先恢复业务数据库连接。", false);
        }
    }

    private ReadinessCheck ready(String id, String name, String detail, boolean required) {
        return new ReadinessCheck(id, name, "READY", detail, "", required);
    }

    private ReadinessCheck warning(String id, String name, String detail, String action, boolean required) {
        return new ReadinessCheck(id, name, "WARNING", detail, action, required);
    }

    private ReadinessCheck failed(String id, String name, String detail, String action, boolean required) {
        return new ReadinessCheck(id, name, "FAILED", detail, action, required);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private String safe(Exception exception) {
        var message = exception.getMessage();
        if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
        return message.length() <= 300 ? message : message.substring(0, 300) + "…";
    }
}

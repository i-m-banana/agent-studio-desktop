package com.agentstudio.adapter.ssh;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.agentstudio.tool.AgentTool;
import com.agentstudio.tool.ToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class BuildReleaseCandidateImageTool implements AgentTool {
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "build_release_candidate_image", "构建候选应用镜像",
            "审批后按候选 ID 和清单 SHA-256 校验固定材料，在独立限额构建器中生成唯一镜像；不切换生产、不重启网站。构建消耗资源并可能下载构建器和基础镜像。",
            "SSH", "EXECUTE", "HIGH", 1140,
            Map.of("type", "object", "properties", Map.of(
                    "releaseId", Map.of("type", "string", "pattern", "^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$"),
                    "manifestSha256", Map.of("type", "string", "pattern", "^[0-9a-f]{64}$")),
                    "required", List.of("releaseId", "manifestSha256"), "additionalProperties", false));
    private final RemoteDeploymentWorkspace workspace;
    private final RemoteDeploymentService profiles;
    private final ObjectMapper mapper;
    private final Duration timeout;
    private final ReleaseImageCommands commands = new ReleaseImageCommands();

    @Autowired
    public BuildReleaseCandidateImageTool(RemoteDeploymentWorkspace workspace, RemoteDeploymentService profiles,
                                         ObjectMapper mapper) {
        this(workspace, profiles, mapper, Duration.ofSeconds(1100));
    }

    BuildReleaseCandidateImageTool(RemoteDeploymentWorkspace workspace, RemoteDeploymentService profiles,
                                  ObjectMapper mapper, Duration timeout) {
        this.workspace = workspace; this.profiles = profiles; this.mapper = mapper; this.timeout = timeout;
    }

    @Override public ToolDescriptor descriptor() { return DESCRIPTOR; }
    @Override public String targetEnvironment() {
        return "SSH:" + profiles.approvalTarget() + "|IMAGE:BUILD_ONLY|LIMIT:512MiB,0.5CPU," + ReleaseImageCommands.BUILD_BUDGET_SECONDS + "s|MIN:2GiB_DISK,768MiB_AVAILABLE"
                + "|REGISTRY_RETRY:3_MAX,SHARED_" + ReleaseImageCommands.BUILD_BUDGET_SECONDS + "s,5s_BACKOFF,TRANSPORT_ONLY"
                + "|REGISTRY_MIRRORS:" + String.join(",", ReleaseImageCommands.REGISTRY_MIRRORS)
                + "|BUILDKIT_CONFIG_SHA256:" + ReleaseImageCommands.configSha256();
    }

    @Override public String execute(JsonNode arguments) throws Exception {
        if (!arguments.isObject() || arguments.size() != 2 || !arguments.path("releaseId").isTextual()
                || !arguments.path("manifestSha256").isTextual())
            throw new IllegalArgumentException("镜像构建只接受候选 ID 和清单摘要，不接受命令、路径、标签、参数或环境变量");
        var releaseId = arguments.path("releaseId").asText();
        var manifestSha = arguments.path("manifestSha256").asText();
        if (!releaseId.matches("[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}") || !manifestSha.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("候选 ID 或清单 SHA-256 格式无效");
        var profile = profiles.current();
        var started = System.nanoTime();
        return workspace.execute(profile, (session, access, properties) -> {
            var candidate = access.validateCandidate(releaseId);
            var manifest = access.readCandidateManifest(candidate);
            if (!manifestSha.equals(RemoteReleaseCandidateStager.sha256(manifest)))
                throw new SecurityException("候选清单摘要不匹配，未开始镜像构建");
            var hashes = parseManifest(manifest, releaseId);
            var attemptId = UUID.randomUUID().toString().replace("-", "");
            var attempt = access.createImageAttempt(candidate, attemptId);
            var builder = "agentstudio-" + attemptId;
            var image = "agentstudio-candidate:" + releaseId + "-" + attemptId;
            CommandResult result;
            try {
                result = run(session, commands.build(candidate, attempt, builder, image, releaseId, manifestSha, hashes),
                        properties.connectTimeout(), timeout);
            } catch (Exception failure) {
                // Interrupted callers still attempt bounded cleanup on the same authenticated session.
                var interrupted = Thread.interrupted();
                String cleanupStatus;
                try {
                    var cleanup = run(session, commands.cleanup(attempt, builder), properties.connectTimeout(), Duration.ofSeconds(30));
                    cleanupStatus = "清理退出码=" + cleanup.exitCode() + "\n清理输出：\n" + cleanup.output();
                }
                catch (Exception cleanupFailure) { cleanupStatus = "清理未确认：" + cleanupFailure.getMessage(); }
                finally { if (interrupted || failure instanceof InterruptedException) Thread.currentThread().interrupt(); }
                var message = failure.getMessage() + "\nbuilder=" + builder + "\nattempt=" + attempt + "\n" + cleanupStatus;
                if (failure instanceof InterruptedException) {
                    var cancelled = new InterruptedException(message); cancelled.initCause(failure); throw cancelled;
                }
                throw new IllegalStateException(message, failure);
            }
            var fields = new LinkedHashMap<String, String>();
            if (result.exitCode() != 0) {
                CommandResult cleanup;
                try {
                    cleanup = run(session, commands.cleanup(attempt, builder), properties.connectTimeout(), Duration.ofSeconds(30));
                } catch (Exception cleanupFailure) {
                    throw new IllegalStateException(failureDetails(result, builder, attempt)
                            + "\n清理连接或执行失败：" + cleanupFailure.getMessage(), cleanupFailure);
                }
                if (cleanup.exitCode() != 0) throw new IllegalStateException(failureDetails(result, builder, attempt)
                        + "\n清理未确认：exitCode=" + cleanup.exitCode() + "\n" + cleanup.output());
                if (result.exitCode() == 124) throw new IllegalStateException(failureDetails(result, builder, attempt)
                        + "\n远程阶段超时；专用构建器清理已确认");
                if (result.exitCode() == 31) throw new SecurityException(failureDetails(result, builder, attempt)
                        + "\n候选或构建快照摘要不匹配，已拒绝构建");
            }
            if (result.exitCode() == 0) {
                var marker = result.output().lastIndexOf("\nAGENTSTUDIO_IMAGE_RECEIPT\n");
                if (marker < 0) throw new IllegalStateException("镜像构建没有成功回执");
                result.output().substring(marker).lines().forEach(line -> {
                    var at = line.indexOf('='); if (at > 0 && fields.put(line.substring(0, at), line.substring(at + 1)) != null)
                        throw new IllegalStateException("镜像回执字段重复");
                });
                if (!releaseId.equals(fields.get("RELEASE_ID")) || !manifestSha.equals(fields.get("MANIFEST_SHA256"))
                        || !ReleaseImageCommands.configSha256().equals(fields.get("BUILDKIT_CONFIG_SHA256"))
                        || !image.equals(fields.get("IMAGE_TAG")) || !"true".equals(fields.get("BUILDER_CLEANED"))
                        || !fields.getOrDefault("IMAGE_ID", "").matches("sha256:[0-9a-f]{64}"))
                    throw new IllegalStateException("镜像构建回执校验失败");
            }
            var response = new LinkedHashMap<String, Object>();
            response.put("releaseId", releaseId); response.put("manifestSha256", manifestSha);
            response.put("artifactSha256", hashes.get("artifactSha256"));
            response.put("target", profiles.target(profile)); response.put("candidatePath", candidate);
            response.put("buildAttemptPath", attempt); response.put("imageTag", image);
            response.put("builderName", builder);
            response.put("registryMirrors", ReleaseImageCommands.REGISTRY_MIRRORS);
            response.put("buildkitConfigSha256", ReleaseImageCommands.configSha256());
            response.put("imageId", fields.get("IMAGE_ID")); response.put("stage", result.exitCode() == 0 ? "IMAGE_READY" : failedStage(result.output()));
            response.put("successful", result.exitCode() == 0); response.put("exitCode", result.exitCode());
            response.put("durationMs", Duration.ofNanos(System.nanoTime() - started).toMillis());
            response.put("output", result.output()); response.put("outputTruncated", result.truncated());
            response.put("productionModified", false); response.put("servicesRestarted", false);
            response.put("imageBuilt", result.exitCode() == 0);
            return mapper.writeValueAsString(response);
        });
    }

    static Map<String, String> parseManifest(byte[] bytes, String releaseId) {
        var fields = new LinkedHashMap<String, String>();
        new String(bytes, StandardCharsets.UTF_8).lines().forEach(line -> {
            var at = line.indexOf('=');
            if (at <= 0 || fields.put(line.substring(0, at), line.substring(at + 1)) != null)
                throw new IllegalArgumentException("候选清单字段无效或重复");
        });
        if (!"2".equals(fields.get("candidateFormat")))
            throw new IllegalArgumentException("候选清单版本不支持镜像构建，请重新准备候选（格式 2）");
        if (!releaseId.equals(fields.get("releaseId")) || !"target/app.jar".equals(fields.get("artifactPath"))
                || !"false".equals(fields.get("productionModified"))
                || !"app.jar,Dockerfile,compose.yml,nginx.conf".equals(fields.get("files")))
            throw new SecurityException("候选清单身份或固定材料无效");
        for (var field : List.of("artifactSha256", "dockerfileSha256", "composeSha256", "nginxSha256"))
            if (!fields.getOrDefault(field, "").matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("候选清单缺少固定文件摘要：" + field);
        return fields;
    }

    private static String failedStage(String output) {
        var stage = "IMAGE_BUILD";
        for (var line : output.lines().toList()) {
            if (line.startsWith("AGENTSTUDIO_STAGE=")) stage = line.substring("AGENTSTUDIO_STAGE=".length());
            if (line.startsWith("FAILED_STAGE=")) stage = line.substring("FAILED_STAGE=".length());
        }
        return stage;
    }

    private static String failureDetails(CommandResult result, String builder, String attempt) {
        return "镜像构建失败：stage=" + failedStage(result.output()) + ", exitCode=" + result.exitCode()
                + ", outputTruncated=" + result.truncated() + "\nbuilder=" + builder + "\nattempt=" + attempt
                + "\n原始构建输出：\n" + result.output();
    }

    private CommandResult run(ClientSession session, String command, Duration connectTimeout, Duration budget) throws Exception {
        var output = new BoundedSshOutputStream(16_000);
        try (var channel = session.createExecChannel(command)) {
            channel.setOut(output); channel.setRedirectErrorStream(true);
            channel.open().verify(connectTimeout);
            var deadline = System.nanoTime() + budget.toNanos();
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    channel.close(true); throw new InterruptedException("镜像构建已中断，已关闭远程命令通道并请求清理构建器；原始输出：\n" + output.value());
                }
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    channel.close(true); throw new IllegalStateException("镜像构建超时，已关闭远程命令通道并请求清理构建器；原始输出：\n" + output.value());
                }
                if (channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), Math.min(250,
                        Math.max(1, Duration.ofNanos(remaining).toMillis()))).contains(ClientChannelEvent.CLOSED)) break;
            }
            var exit = channel.getExitStatus();
            if (exit == null) throw new IllegalStateException("镜像命令未返回退出码");
            return new CommandResult(exit, output.value(), output.truncated());
        }
    }

    private record CommandResult(int exitCode, String output, boolean truncated) {}
}

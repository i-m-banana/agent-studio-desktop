package com.agentstudio.adapter.ssh;

import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.agentstudio.tool.AgentTool;
import com.agentstudio.tool.ToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class PrepareReleaseCandidateTool implements AgentTool {
    private static final DateTimeFormatter RELEASE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "prepare_release_candidate", "准备不可变发布候选",
            "审批后从固定本地源码执行 Maven 测试与打包，只接受 target/app.jar，并将固定部署材料上传到全新的远程候选目录后校验摘要；不接受模型参数，不修改生产目录。",
            "SSH", "WRITE", "HIGH", 540,
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false));

    private final RemoteDeploymentService profiles;
    private final LocalReleaseCandidateBuilder builder;
    private final RemoteReleaseCandidateStager stager;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final java.util.function.Supplier<String> randomSuffix;

    @Autowired
    PrepareReleaseCandidateTool(RemoteDeploymentService profiles, LocalReleaseCandidateBuilder builder,
                                RemoteReleaseCandidateStager stager, ObjectMapper objectMapper) {
        this(profiles, builder, stager, objectMapper, Clock.systemUTC(),
                () -> UUID.randomUUID().toString().replace("-", "").substring(0, 8));
    }

    PrepareReleaseCandidateTool(RemoteDeploymentService profiles, LocalReleaseCandidateBuilder builder,
                                RemoteReleaseCandidateStager stager, ObjectMapper objectMapper, Clock clock) {
        this(profiles, builder, stager, objectMapper, clock,
                () -> UUID.randomUUID().toString().replace("-", "").substring(0, 8));
    }

    PrepareReleaseCandidateTool(RemoteDeploymentService profiles, LocalReleaseCandidateBuilder builder,
                                RemoteReleaseCandidateStager stager, ObjectMapper objectMapper, Clock clock,
                                java.util.function.Supplier<String> randomSuffix) {
        this.profiles = profiles; this.builder = builder; this.stager = stager;
        this.objectMapper = objectMapper; this.clock = clock; this.randomSuffix = randomSuffix;
    }

    @Override public ToolDescriptor descriptor() { return DESCRIPTOR; }
    @Override public String targetEnvironment() {
        var profile = profiles.current();
        return "SSH:" + profiles.approvalTarget() + "|RELEASE_ROOT:"
                + profile.remoteDeployRoot().replaceAll("/+$", "") + "-releases|RELEASE:PREPARE_ONLY";
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        if (!arguments.isObject() || !arguments.isEmpty()) {
            throw new IllegalArgumentException("发布候选准备不接受路径、制品、命令、参数、环境变量、版本号、覆盖或部署选项");
        }
        var started = System.nanoTime(); var profile = profiles.current();
        var build = builder.build(profile);
        if (!build.successful()) return result(profile, null, build, null, null,
                Duration.ofNanos(System.nanoTime() - started).toMillis());

        var suffix = randomSuffix.get();
        if (suffix == null || !suffix.matches("[0-9a-f]{8}")) throw new IllegalStateException("平台随机发布后缀无效");
        var releaseId = RELEASE_TIME.format(clock.instant()) + "-" + suffix;
        var artifactSha = RemoteReleaseCandidateStager.sha256(build.artifact());
        var files = new LinkedHashMap<String, java.nio.file.Path>();
        files.put("app.jar", build.artifact());
        files.put("Dockerfile", build.sourceRoot().resolve("Dockerfile"));
        files.put("compose.yml", build.sourceRoot().resolve(profile.localComposeFile()));
        files.put("nginx.conf", build.sourceRoot().resolve(profile.nginxConfig()));
        var manifest = "candidateFormat=1\nreleaseId=" + releaseId + "\ncreatedAt=" + clock.instant()
                + "\nartifactPath=target/app.jar\nartifactSha256=" + artifactSha
                + "\ncomposeSource=" + profile.localComposeFile()
                + "\nproductionModified=false\nfiles=app.jar,Dockerfile,compose.yml,nginx.conf\n";
        var stage = stager.stage(profile, releaseId, files, artifactSha, manifest);
        return result(profile, releaseId, build, stage, artifactSha,
                Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    private String result(RemoteDeploymentProfile profile, String releaseId,
                          LocalReleaseCandidateBuilder.BuildResult build,
                          RemoteReleaseCandidateStager.StageResult stage, String artifactSha, long durationMs) throws Exception {
        var response = new LinkedHashMap<String, Object>();
        response.put("releaseId", releaseId); response.put("target", profiles.target(profile));
        response.put("localSourceRoot", profile.localSourceRoot());
        response.put("artifactPath", "target/app.jar");
        response.put("remoteReleaseRoot", stage == null ? profile.remoteDeployRoot() + "-releases" : stage.releaseRoot());
        response.put("candidatePath", stage == null ? null : stage.candidatePath());
        response.put("stage", stage == null ? build.stage() : "REMOTE_VERIFY");
        response.put("successful", build.successful() && stage != null && stage.successful());
        response.put("exitCode", !build.successful() ? build.exitCode() : stage.exitCode());
        response.put("durationMs", durationMs);
        response.put("artifactBytes", build.successful() ? Files.size(build.artifact()) : 0);
        response.put("artifactSha256", artifactSha);
        response.put("manifestSha256", stage == null ? null : stage.fields().get("MANIFEST_SHA256"));
        var output = build.output() + (stage == null ? "" : "\n--- REMOTE_VERIFY ---\n" + stage.output());
        response.put("output", output); response.put("outputTruncated",
                build.outputTruncated() || stage != null && stage.outputTruncated());
        response.put("productionModified", false); response.put("imageBuilt", false);
        return objectMapper.writeValueAsString(response);
    }
}

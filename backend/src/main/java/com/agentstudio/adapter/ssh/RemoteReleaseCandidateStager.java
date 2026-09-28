package com.agentstudio.adapter.ssh;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.sftp.client.SftpClient;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
class RemoteReleaseCandidateStager {
    private static final int OUTPUT_LIMIT = 8_000;
    private final RemoteDeploymentWorkspace workspace;
    private final ReleaseCandidateCommands commands;
    private final Duration timeout;

    @Autowired
    RemoteReleaseCandidateStager(RemoteDeploymentWorkspace workspace) {
        this(workspace, new ReleaseCandidateCommands(), Duration.ofSeconds(45));
    }

    RemoteReleaseCandidateStager(RemoteDeploymentWorkspace workspace, ReleaseCandidateCommands commands,
                                 Duration timeout) {
        this.workspace = workspace; this.commands = commands; this.timeout = timeout;
    }

    StageResult stage(RemoteDeploymentProfile profile, String releaseId, Map<String, Path> files,
                      String artifactSha256, String manifest) throws Exception {
        return workspace.execute(profile, (session, access, properties) -> {
            var releaseRoot = access.releaseRoot();
            var candidatePath = access.createCandidateDirectory(releaseId);
            for (var entry : files.entrySet()) access.uploadExclusive(candidatePath, entry.getKey(), entry.getValue());
            access.uploadExclusive(candidatePath, "manifest.properties",
                    manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var checksums = new StringBuilder();
            for (var entry : files.entrySet()) checksums.append(sha256(entry.getValue())).append("  ")
                    .append(entry.getKey()).append('\n');
            checksums.append(sha256(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .append("  manifest.properties\n");
            access.uploadExclusive(candidatePath, "SHA256SUMS",
                    checksums.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

            var output = new BoundedSshOutputStream(OUTPUT_LIMIT); var started = System.nanoTime();
            try (var channel = session.createExecChannel(commands.verify(candidatePath, releaseId, artifactSha256))) {
                channel.setOut(output); channel.setRedirectErrorStream(true);
                channel.open().verify(properties.connectTimeout());
                var deadline = System.nanoTime() + timeout.toNanos();
                while (true) {
                    if (Thread.currentThread().isInterrupted()) {
                        channel.close(true); throw new InterruptedException("候选版本校验已中断，已关闭远程命令通道");
                    }
                    var remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        channel.close(true); throw new IllegalStateException("候选版本校验超时，已关闭远程命令通道");
                    }
                    if (channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED),
                            Math.min(250, Math.max(1, Duration.ofNanos(remaining).toMillis())))
                            .contains(ClientChannelEvent.CLOSED)) break;
                }
                var exit = channel.getExitStatus();
                if (exit == null) throw new IllegalStateException("候选版本校验通道未返回退出码");
                var duration = Duration.ofNanos(System.nanoTime() - started).toMillis();
                if (exit != 0) return new StageResult(false, exit, duration, releaseRoot, candidatePath,
                        output.value(), output.truncated(), Map.of());
                if (output.truncated()) throw new IllegalStateException("候选版本成功回执超过输出上限");
                var fields = parse(output.value());
                if (!releaseId.equals(fields.get("RELEASE_ID")) || !candidatePath.equals(fields.get("CANDIDATE_PATH"))
                        || !artifactSha256.equals(fields.get("ARTIFACT_SHA256"))
                        || !fields.getOrDefault("MANIFEST_SHA256", "").matches("[0-9a-f]{64}")
                        || number(fields.get("ARTIFACT_BYTES")) <= 0 || number(fields.get("FILE_COUNT")) != 6) {
                    throw new IllegalStateException("远程候选版本回执不完整或校验信息无效");
                }
                return new StageResult(true, 0, duration, releaseRoot, candidatePath,
                        "远程候选目录与固定文件摘要已校验", false, fields);
            }
        });
    }

    static String sha256(Path path) throws Exception { try (var input = Files.newInputStream(path)) { return sha256(input); } }
    static String sha256(byte[] value) throws Exception { return java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(value)); }
    private static String sha256(java.io.InputStream input) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256"); input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest));
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
    private Map<String, String> parse(String output) {
        var fields = new LinkedHashMap<String, String>();
        output.lines().forEach(line -> { var at = line.indexOf('='); if (at > 0) fields.put(line.substring(0, at), line.substring(at + 1)); });
        return fields;
    }
    private long number(String value) { try { return value == null ? 0 : Long.parseLong(value); } catch (NumberFormatException ignored) { return 0; } }

    record StageResult(boolean successful, int exitCode, long durationMs, String releaseRoot,
                       String candidatePath, String output, boolean outputTruncated, Map<String, String> fields) {}
}

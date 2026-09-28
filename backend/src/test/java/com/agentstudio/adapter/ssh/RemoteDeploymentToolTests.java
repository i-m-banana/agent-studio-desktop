package com.agentstudio.adapter.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.agentstudio.secret.SecretResolver;
import com.agentstudio.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.SftpModuleProperties;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteDeploymentToolTests {
    @TempDir Path root;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> receivedCommands = new ArrayList<>();
    private final AtomicInteger sessionsCreated = new AtomicInteger();
    private final AtomicBoolean hangingDestroyed = new AtomicBoolean();
    private final AtomicReference<Integer> backupExitCode = new AtomicReference<>(0);
    private final AtomicReference<Integer> restoreDrillExitCode = new AtomicReference<>(0);
    private final AtomicReference<String> releaseArtifactSha = new AtomicReference<>();
    private SshServer server;
    private SshWorkspaceProperties ssh;
    private RemoteDeploymentProfile profile;

    @BeforeEach
    void startServer() throws Exception {
        var deploy = root.resolve("srv/old-things");
        Files.createDirectories(deploy.resolve("data/uploads"));
        Files.createDirectories(root.resolve("srv/backups"));
        Files.writeString(deploy.resolve("compose.yml"), "services: {}");
        Files.writeString(deploy.resolve("Dockerfile"), "FROM scratch");
        Files.writeString(deploy.resolve("app.jar"), "fixture");
        Files.writeString(deploy.resolve("nginx.conf"), "server {}");
        Files.writeString(deploy.resolve(".env"), "SECRET=never-return-this");

        server = SshServer.setUpDefaultServer(); server.setPort(0);
        var keys = new SimpleGeneratorHostKeyProvider(root.resolve("host-key.ser"));
        server.setKeyPairProvider(keys);
        server.setPasswordAuthenticator((username, password, session) ->
                username.equals("tester") && password.equals("password"));
        server.setFileSystemFactory(new VirtualFileSystemFactory(root));
        SftpModuleProperties.SFTP_VERSION.set(server, 3);
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
        server.setCommandFactory((channel, command) -> {
            synchronized (receivedCommands) { receivedCommands.add(command); }
            if (command.contains("mysqldump")) {
                var backupId = "20260921T120000Z-deadbeef";
                var backupOutput = "BACKUP_ID=" + backupId + "\n"
                        + "BACKUP_PATH=/srv/backups/" + backupId + "\n"
                        + "MANIFEST_SHA256=" + "a".repeat(64) + "\n"
                        + "DATABASE_BYTES=2048\nUPLOADS_BYTES=4096\nFILE_COUNT=9\n";
                var exit = backupExitCode.get();
                return new FixtureCommand(exit != null && exit == 0 ? backupOutput : "",
                        exit != null && exit != 0 ? "backup failed\n" : "", exit,
                        exit == null ? hangingDestroyed : null);
            }
            if (command.contains("RESTORED_UPLOADS_BYTES")) {
                var backupId = "20260921T120000Z-deadbeef";
                var drillId = "restore-20260921T130000Z-cafebabe";
                var drillOutput = "BACKUP_ID=" + backupId + "\n"
                        + "BACKUP_PATH=/srv/backups/" + backupId + "\n"
                        + "DRILL_ID=" + drillId + "\n"
                        + "DRILL_PATH=/srv/backups/restore-drills/" + drillId + "\n"
                        + "MANIFEST_SHA256=" + "a".repeat(64) + "\n"
                        + "DRILL_SHA256=" + "b".repeat(64) + "\n"
                        + "DATABASE_BYTES=2048\nRESTORED_UPLOADS_BYTES=8192\nRESTORED_FILE_COUNT=12\n";
                var exit = restoreDrillExitCode.get();
                return new FixtureCommand(exit != null && exit == 0 ? drillOutput : "",
                        exit != null && exit != 0 ? "restore drill failed\n" : "", exit,
                        exit == null ? hangingDestroyed : null);
            }
            if (command.contains("CANDIDATE_PATH")) {
                var releaseId = "20260921T150000Z-cafebabe";
                var candidate = "/srv/old-things-releases/" + releaseId;
                return new FixtureCommand("RELEASE_ID=" + releaseId + "\nCANDIDATE_PATH=" + candidate
                        + "\nARTIFACT_SHA256=" + releaseArtifactSha.get()
                        + "\nARTIFACT_BYTES=8\nMANIFEST_SHA256=" + "b".repeat(64)
                        + "\nFILE_COUNT=6\n", "", 0, null);
            }
            if (command.contains("config --quiet")) return new FixtureCommand(null, null, null, hangingDestroyed);
            if (command.contains("nginx -t")) return new FixtureCommand("", "invalid nginx\n", 1, null);
            if (command.contains("sha256sum")) return new FixtureCommand("x".repeat(20_000), "", 0, null);
            return new FixtureCommand("diagnostic-ok\n", "", 0, null);
        });
        server.addSessionListener(new SessionListener() {
            @Override public void sessionCreated(Session session) { sessionsCreated.incrementAndGet(); }
        });
        server.start();
        var hostKey = keys.loadKeys(null).iterator().next().getPublic();
        ssh = new SshWorkspaceProperties("127.0.0.1", server.getPort(), "tester", "/workspace",
                KeyUtils.getFingerPrint(BuiltinDigests.sha256, hostKey), "TEST_SSH_PASSWORD", Duration.ofSeconds(5));
        profile = new RemoteDeploymentProfile("D:/source", "/srv/old-things", "/srv/backups",
                "docker-compose.yml", "compose.yml", "old-things", "nginx.conf", "http://127.0.0.1/",
                true, "READY", null, null, java.time.Instant.now());
    }

    @AfterEach void stopServer() throws Exception { if (server != null) server.stop(true); }

    @Test
    void registersOnlyFixedHighRiskDeploymentDiagnosticsAndUsesOneSession() throws Exception {
        var tool = tool(Duration.ofSeconds(5));
        var registry = new ToolRegistry(List.of(tool), objectMapper);
        var descriptor = registry.descriptor("inspect_remote_deployment");
        assertThat(descriptor.source()).isEqualTo("SSH");
        assertThat(descriptor.capability()).isEqualTo("EXECUTE");
        assertThat(descriptor.riskLevel()).isEqualTo("HIGH");
        assertThat(descriptor.inputSchema().toString()).doesNotContain("path", "command", "service", "url", "environment");

        var result = objectMapper.readTree(tool.execute(objectMapper.readTree("{\"task\":\"COMPOSE_STATUS\"}")));
        assertThat(result.path("successful").asBoolean()).isTrue();
        assertThat(result.path("deploymentRoot").asText()).isEqualTo("/srv/old-things");
        assertThat(result.path("composeProject").asText()).isEqualTo("old-things");
        assertThat(result.path("output").asText()).contains("diagnostic-ok").doesNotContain("never-return-this");
        assertThat(sessionsCreated).hasValue(1);
    }

    @Test
    void mapsExactlyFiveCommandsWithoutReadingEnvironmentFile() {
        var commands = new RemoteDeploymentCommands();
        assertThat(commands.command("COMPOSE_VALIDATE", profile)).contains("config --quiet");
        assertThat(commands.command("COMPOSE_STATUS", profile)).contains("ps --format json 'nginx' 'app' 'mysql' 'phpmyadmin'");
        assertThat(commands.command("NGINX_VALIDATE", profile)).endsWith("exec -T nginx nginx -t");
        assertThat(commands.command("SITE_HEALTH", profile)).contains("http://127.0.0.1/");
        assertThat(commands.command("RELEASE_FINGERPRINT", profile)).contains("sha256sum", "stat", "app.jar");
        for (var task : RemoteDeploymentCommands.TASKS) {
            assertThat(commands.command(task, profile)).doesNotContain(".env", "cat ");
        }
        assertThatThrownBy(() -> commands.command("DOCKER_RESTART", profile)).hasMessageContaining("不支持");
    }

    @Test
    void returnsNonZeroExitAsAuditableResult() throws Exception {
        var result = objectMapper.readTree(tool(Duration.ofSeconds(5)).execute(
                objectMapper.readTree("{\"task\":\"NGINX_VALIDATE\"}")));
        assertThat(result.path("successful").asBoolean()).isFalse();
        assertThat(result.path("exitCode").asInt()).isEqualTo(1);
        assertThat(result.path("output").asText()).contains("invalid nginx");
    }

    @Test
    void truncatesOutputAndClosesTimedOutChannel() throws Exception {
        var fingerprint = objectMapper.readTree(tool(Duration.ofSeconds(5)).execute(
                objectMapper.readTree("{\"task\":\"RELEASE_FINGERPRINT\"}")));
        assertThat(fingerprint.path("outputTruncated").asBoolean()).isTrue();
        assertThat(fingerprint.path("output").asText()).contains("输出已截断").hasSizeLessThan(16_100);

        assertThatThrownBy(() -> tool(Duration.ofMillis(150)).execute(
                objectMapper.readTree("{\"task\":\"COMPOSE_VALIDATE\"}")))
                .hasMessageContaining("已关闭远程命令通道");
        awaitTrue(hangingDestroyed);
    }

    @Test
    void rejectsUnknownOrExtraControlParametersBeforeExec() {
        var tool = tool(Duration.ofSeconds(5));
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree("{\"task\":\"SHELL\"}")))
                .hasMessageContaining("五种固定");
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree(
                "{\"task\":\"COMPOSE_STATUS\",\"command\":\"docker compose down\"}")))
                .hasMessageContaining("只接受固定 task");
        assertThat(receivedCommands).isEmpty();
    }

    @Test
    void createsOnlyFixedHighRiskBackupAndReturnsVerifiedManifestMetadata() throws Exception {
        var tool = backupTool(Duration.ofSeconds(5));
        var descriptor = new ToolRegistry(List.of(tool), objectMapper)
                .descriptor("prepare_remote_deployment_backup");
        assertThat(descriptor.source()).isEqualTo("SSH");
        assertThat(descriptor.capability()).isEqualTo("WRITE");
        assertThat(descriptor.riskLevel()).isEqualTo("HIGH");
        assertThat(descriptor.inputSchema().toString()).doesNotContain("path", "command", "name", "environment");

        var result = objectMapper.readTree(tool.execute(objectMapper.readTree("{}")));
        assertThat(result.path("successful").asBoolean()).isTrue();
        assertThat(result.path("backupId").asText()).isEqualTo("20260921T120000Z-deadbeef");
        assertThat(result.path("backupPath").asText()).isEqualTo("/srv/backups/20260921T120000Z-deadbeef");
        assertThat(result.path("databaseBytes").asLong()).isEqualTo(2048);
        assertThat(result.path("uploadsBytes").asLong()).isEqualTo(4096);
        assertThat(result.path("fileCount").asInt()).isEqualTo(9);
        assertThat(result.path("manifestSha256").asText()).hasSize(64);
        assertThat(result.path("output").asText()).doesNotContain("never-return-this", "MYSQL_ROOT_PASSWORD");
        assertThat(sessionsCreated).hasValue(1);
    }

    @Test
    void backupCommandIsCreateOnlyAndKeepsSecretsInsideFixedContainerCommand() {
        var command = new RemoteDeploymentBackupCommands().command(profile);
        assertThat(command).contains("mkdir -- \"$backup_dir\"", "mysqldump --single-transaction",
                "tar -czf", "install -m 600 -- .env", "sha256sum -c SHA256SUMS > /dev/null",
                "sha256sum -c manifest.sha256 > /dev/null", "FAILED");
        assertThat(command).contains("-p\"$MYSQL_ROOT_PASSWORD\"");
        assertThat(command).doesNotContain("never-return-this", " rm ", "rm -", "docker compose down",
                "volume rm", "system prune", "cat .env");
    }

    @Test
    void backupReturnsNonZeroAsAuditableResultAndClosesTimeout() throws Exception {
        backupExitCode.set(7);
        var failed = objectMapper.readTree(backupTool(Duration.ofSeconds(5)).execute(objectMapper.readTree("{}")));
        assertThat(failed.path("successful").asBoolean()).isFalse();
        assertThat(failed.path("exitCode").asInt()).isEqualTo(7);
        assertThat(failed.path("output").asText()).contains("backup failed");

        backupExitCode.set(null);
        assertThatThrownBy(() -> backupTool(Duration.ofMillis(150)).execute(objectMapper.readTree("{}")))
                .hasMessageContaining("已关闭远程命令通道");
        awaitTrue(hangingDestroyed);
    }

    @Test
    void backupRejectsEveryModelControlledParameterBeforeExec() {
        var tool = backupTool(Duration.ofSeconds(5));
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree("{\"path\":\"/tmp\"}")))
                .hasMessageContaining("不接受路径");
        assertThat(receivedCommands).isEmpty();
    }

    @Test
    void restoresOnlyLatestBackupIntoFixedIsolatedDrillDirectory() throws Exception {
        var tool = restoreDrillTool(Duration.ofSeconds(5));
        var descriptor = new ToolRegistry(List.of(tool), objectMapper)
                .descriptor("verify_remote_deployment_backup_restore");
        assertThat(descriptor.source()).isEqualTo("SSH");
        assertThat(descriptor.capability()).isEqualTo("WRITE");
        assertThat(descriptor.riskLevel()).isEqualTo("HIGH");
        assertThat(descriptor.inputSchema().toString())
                .doesNotContain("backupId", "path", "command", "environment", "database");

        var result = objectMapper.readTree(tool.execute(objectMapper.readTree("{}")));
        assertThat(result.path("successful").asBoolean()).isTrue();
        assertThat(result.path("backupId").asText()).isEqualTo("20260921T120000Z-deadbeef");
        assertThat(result.path("drillId").asText()).isEqualTo("restore-20260921T130000Z-cafebabe");
        assertThat(result.path("drillPath").asText())
                .isEqualTo("/srv/backups/restore-drills/restore-20260921T130000Z-cafebabe");
        assertThat(result.path("databaseBytes").asLong()).isEqualTo(2048);
        assertThat(result.path("restoredUploadsBytes").asLong()).isEqualTo(8192);
        assertThat(result.path("restoredFileCount").asLong()).isEqualTo(12);
        assertThat(result.path("productionModified").asBoolean()).isFalse();
        assertThat(result.path("databaseImported").asBoolean()).isFalse();
        assertThat(sessionsCreated).hasValue(1);
    }

    @Test
    void restoreDrillCommandVerifiesThenMaterializesWithoutProductionOperations() {
        var command = new RemoteDeploymentRestoreDrillCommands().command(profile);
        assertThat(command).contains("sha256sum -c SHA256SUMS > /dev/null",
                "sha256sum -c manifest.sha256 > /dev/null", "gzip -t uploads.tar.gz",
                "restore-drills", "--keep-old-files", "cmp -- database.sql",
                "productionModified=false", "test ! -L", "backupFormat=1",
                "deploymentRoot=$expected_deploy_root", "composeProject=$expected_compose_project",
                "files=database.sql,uploads.tar.gz,app.jar,Dockerfile,compose.yml,nginx.conf,.env,images.json,services.json",
                "df -PB1", "required_bytes", "268435456");
        assertThat(command).doesNotContain("docker ", "mysql ", "mysqldump", " rm ", "rm -",
                "compose down", "volume rm", "system prune", "mv --", "/srv/old-things/");
    }

    @Test
    void restoreDrillReturnsNonZeroAndClosesTimedOutChannel() throws Exception {
        restoreDrillExitCode.set(8);
        var failed = objectMapper.readTree(restoreDrillTool(Duration.ofSeconds(5))
                .execute(objectMapper.readTree("{}")));
        assertThat(failed.path("successful").asBoolean()).isFalse();
        assertThat(failed.path("exitCode").asInt()).isEqualTo(8);
        assertThat(failed.path("output").asText()).contains("restore drill failed");

        restoreDrillExitCode.set(null);
        assertThatThrownBy(() -> restoreDrillTool(Duration.ofMillis(150)).execute(objectMapper.readTree("{}")))
                .hasMessageContaining("已关闭远程命令通道");
        awaitTrue(hangingDestroyed);
    }

    @Test
    void restoreDrillRejectsEveryModelControlledParameterBeforeExec() {
        var tool = restoreDrillTool(Duration.ofSeconds(5));
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree(
                "{\"backupId\":\"20260921T120000Z-deadbeef\",\"restoreProduction\":true}")))
                .hasMessageContaining("不接受备份 ID");
        assertThat(receivedCommands).isEmpty();
    }

    @Test
    void preparesOneImmutableCandidateWithNoModelParametersOrProductionMutation() throws Exception {
        var source = root.resolve("local-source"); Files.createDirectories(source.resolve("target"));
        var artifact = source.resolve("target/app.jar"); Files.writeString(artifact, "artifact");
        Files.writeString(source.resolve("Dockerfile"), "FROM scratch");
        Files.writeString(source.resolve("docker-compose.yml"), "services: {}");
        Files.writeString(source.resolve("nginx.conf"), "server {}");
        profile = new RemoteDeploymentProfile(source.toString(), "/srv/old-things", "/srv/backups",
                "docker-compose.yml", "compose.yml", "old-things", "nginx.conf", "http://127.0.0.1/",
                true, "READY", null, null, Instant.now());
        releaseArtifactSha.set(RemoteReleaseCandidateStager.sha256(artifact));
        var builder = mock(LocalReleaseCandidateBuilder.class);
        when(builder.build(profile)).thenReturn(new LocalReleaseCandidateBuilder.BuildResult(true,
                "LOCAL_BUILD", 0, 123, "tests and package passed", false, source, artifact));
        var tool = releaseCandidateTool(builder);
        var descriptor = new ToolRegistry(List.of(tool), objectMapper).descriptor("prepare_release_candidate");
        assertThat(descriptor.source()).isEqualTo("SSH");
        assertThat(descriptor.capability()).isEqualTo("WRITE");
        assertThat(descriptor.riskLevel()).isEqualTo("HIGH");
        assertThat(descriptor.inputSchema().toString()).doesNotContain("path", "artifact", "command", "version");

        var result = objectMapper.readTree(tool.execute(objectMapper.readTree("{}")));
        assertThat(result.path("successful").asBoolean()).isTrue();
        assertThat(result.path("releaseId").asText()).isEqualTo("20260921T150000Z-cafebabe");
        assertThat(result.path("candidatePath").asText())
                .isEqualTo("/srv/old-things-releases/20260921T150000Z-cafebabe");
        assertThat(result.path("artifactPath").asText()).isEqualTo("target/app.jar");
        assertThat(result.path("artifactSha256").asText()).isEqualTo(releaseArtifactSha.get());
        assertThat(result.path("productionModified").asBoolean()).isFalse();
        assertThat(result.path("imageBuilt").asBoolean()).isFalse();
        assertThat(Files.readString(root.resolve("srv/old-things-releases/20260921T150000Z-cafebabe/app.jar")))
                .isEqualTo("artifact");
        assertThat(Files.exists(root.resolve("srv/old-things-releases/20260921T150000Z-cafebabe/.env"))).isFalse();
        assertThat(sessionsCreated).hasValue(1);
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree("{\"artifact\":\"app.jar\"}")))
                .hasMessageContaining("不接受路径");
    }

    @Test
    void failedLocalBuildIsAuditableAndNeverOpensSshSession() throws Exception {
        var builder = mock(LocalReleaseCandidateBuilder.class);
        when(builder.build(profile)).thenReturn(new LocalReleaseCandidateBuilder.BuildResult(false,
                "MAVEN_TEST", 1, 44, "test failed", false, Path.of("D:/source"), Path.of("D:/source/target/app.jar")));
        var result = objectMapper.readTree(releaseCandidateTool(builder).execute(objectMapper.readTree("{}")));
        assertThat(result.path("successful").asBoolean()).isFalse();
        assertThat(result.path("stage").asText()).isEqualTo("MAVEN_TEST");
        assertThat(result.path("exitCode").asInt()).isEqualTo(1);
        assertThat(result.path("output").asText()).contains("test failed");
        assertThat(sessionsCreated).hasValue(0);
    }

    private InspectRemoteDeploymentTool tool(Duration timeout) {
        var secrets = mock(SecretResolver.class);
        when(secrets.resolve("TEST_SSH_PASSWORD")).thenReturn(Optional.of("password"));
        var sshService = mock(SshWorkspaceService.class); when(sshService.current()).thenReturn(ssh);
        var workspace = new RemoteDeploymentWorkspace(new SftpSessionFactory(secrets), sshService);
        var profiles = mock(RemoteDeploymentService.class);
        when(profiles.current()).thenReturn(profile);
        when(profiles.target(profile)).thenReturn("tester@127.0.0.1:" + server.getPort() + "/srv/old-things");
        when(profiles.approvalTarget()).thenReturn(ssh.approvalTarget() + "|DEPLOY:/srv/old-things");
        return new InspectRemoteDeploymentTool(workspace, profiles, objectMapper,
                new RemoteDeploymentCommands(), timeout);
    }

    private PrepareRemoteDeploymentBackupTool backupTool(Duration timeout) {
        var secrets = mock(SecretResolver.class);
        when(secrets.resolve("TEST_SSH_PASSWORD")).thenReturn(Optional.of("password"));
        var sshService = mock(SshWorkspaceService.class); when(sshService.current()).thenReturn(ssh);
        var workspace = new RemoteDeploymentWorkspace(new SftpSessionFactory(secrets), sshService);
        var profiles = mock(RemoteDeploymentService.class);
        when(profiles.current()).thenReturn(profile);
        when(profiles.target(profile)).thenReturn("tester@127.0.0.1:" + server.getPort() + "/srv/old-things");
        when(profiles.approvalTarget()).thenReturn(ssh.approvalTarget() + "|DEPLOY:/srv/old-things");
        return new PrepareRemoteDeploymentBackupTool(workspace, profiles, objectMapper,
                new RemoteDeploymentBackupCommands(), timeout);
    }

    private VerifyRemoteDeploymentBackupRestoreTool restoreDrillTool(Duration timeout) {
        var secrets = mock(SecretResolver.class);
        when(secrets.resolve("TEST_SSH_PASSWORD")).thenReturn(Optional.of("password"));
        var sshService = mock(SshWorkspaceService.class); when(sshService.current()).thenReturn(ssh);
        var workspace = new RemoteDeploymentWorkspace(new SftpSessionFactory(secrets), sshService);
        var profiles = mock(RemoteDeploymentService.class);
        when(profiles.current()).thenReturn(profile);
        when(profiles.target(profile)).thenReturn("tester@127.0.0.1:" + server.getPort() + "/srv/old-things");
        when(profiles.approvalTarget()).thenReturn(ssh.approvalTarget() + "|DEPLOY:/srv/old-things");
        return new VerifyRemoteDeploymentBackupRestoreTool(workspace, profiles, objectMapper,
                new RemoteDeploymentRestoreDrillCommands(), timeout);
    }

    private PrepareReleaseCandidateTool releaseCandidateTool(LocalReleaseCandidateBuilder builder) {
        var secrets = mock(SecretResolver.class);
        when(secrets.resolve("TEST_SSH_PASSWORD")).thenReturn(Optional.of("password"));
        var sshService = mock(SshWorkspaceService.class); when(sshService.current()).thenReturn(ssh);
        var workspace = new RemoteDeploymentWorkspace(new SftpSessionFactory(secrets), sshService);
        var profiles = mock(RemoteDeploymentService.class);
        when(profiles.current()).thenReturn(profile);
        when(profiles.target(profile)).thenReturn("tester@127.0.0.1:" + server.getPort() + "/srv/old-things");
        when(profiles.approvalTarget()).thenReturn(ssh.approvalTarget() + "|DEPLOY:/srv/old-things");
        var stager = new RemoteReleaseCandidateStager(workspace, new ReleaseCandidateCommands(), Duration.ofSeconds(5));
        return new PrepareReleaseCandidateTool(profiles, builder, stager, objectMapper,
                Clock.fixed(Instant.parse("2026-09-21T15:00:00Z"), ZoneOffset.UTC), () -> "cafebabe");
    }

    private static final class FixtureCommand implements Command {
        private final String stdoutText; private final String stderrText; private final Integer exitCode;
        private final AtomicBoolean destroyed; private InputStream input; private OutputStream output;
        private OutputStream error; private ExitCallback callback;
        FixtureCommand(String stdoutText, String stderrText, Integer exitCode, AtomicBoolean destroyed) {
            this.stdoutText = stdoutText; this.stderrText = stderrText; this.exitCode = exitCode; this.destroyed = destroyed;
        }
        @Override public void setInputStream(InputStream input) { this.input = input; }
        @Override public void setOutputStream(OutputStream output) { this.output = output; }
        @Override public void setErrorStream(OutputStream error) { this.error = error; }
        @Override public void setExitCallback(ExitCallback callback) { this.callback = callback; }
        @Override public void start(ChannelSession channel, Environment environment) throws IOException {
            if (exitCode == null) return;
            if (stdoutText != null) output.write(stdoutText.getBytes(StandardCharsets.UTF_8));
            if (stderrText != null) error.write(stderrText.getBytes(StandardCharsets.UTF_8));
            output.flush(); error.flush(); callback.onExit(exitCode);
        }
        @Override public void destroy(ChannelSession channel) throws Exception {
            if (destroyed != null) destroyed.set(true); if (input != null) input.close();
        }
    }

    private static void awaitTrue(AtomicBoolean value) {
        var deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!value.get() && System.nanoTime() < deadline) {
            try { Thread.sleep(10); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); break; }
        }
        assertThat(value).isTrue();
    }
}

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
    private final AtomicReference<Integer> imageExitCode = new AtomicReference<>(0);
    private final AtomicInteger imageCleanupExitCode = new AtomicInteger();
    private final AtomicReference<String> imageFailureOutput = new AtomicReference<>("build failed\n");
    private final AtomicReference<String> imageManifestSha = new AtomicReference<>();
    private final AtomicBoolean imageLargeOutput = new AtomicBoolean();
    private final AtomicBoolean imageBadReceipt = new AtomicBoolean();
    private final AtomicReference<Integer> schemaExitCode = new AtomicReference<>(0);
    private final AtomicReference<Integer> baselineExitCode = new AtomicReference<>(0);
    private final AtomicReference<Integer> publishExitCode = new AtomicReference<>(0);
    private final AtomicReference<String> schemaOutput = new AtomicReference<>(
            "[\"DATABASE\",\"website_fixture\",\"8.0.45\"]\n[\"TABLE\",\"users\",\"BASE TABLE\",\"InnoDB\",\"utf8mb4_unicode_ci\"]\n[\"END\",\"schema-metadata-v1\"]\n");
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
            if (command.contains("/publish.sh")) {
                var exit=publishExitCode.get();
                return new FixtureCommand(exit != null && exit == 0
                        ? "AGENTSTUDIO_PUBLISH_STAGE=DEPLOYED\nAGENTSTUDIO_DEPLOYED=true\nAGENTSTUDIO_ROLLED_BACK=false\nAGENTSTUDIO_MANUAL_REQUIRED=false\nAGENTSTUDIO_MIGRATION_STARTED=1\n"
                        : "AGENTSTUDIO_PUBLISH_STAGE=ROLLED_BACK\nAGENTSTUDIO_DEPLOYED=false\nAGENTSTUDIO_ROLLED_BACK=true\nAGENTSTUDIO_MANUAL_REQUIRED=false\nAGENTSTUDIO_MIGRATION_STARTED=1\n", "",exit,exit==null ? hangingDestroyed : null);
            }
            if (command.contains("CURRENT_IMAGE_ID=")) return new FixtureCommand("CURRENT_IMAGE_ID=sha256:"+"b".repeat(64)+"\nAPP_HEALTH=healthy\nPRODUCTION_SHA256="+"a".repeat(64)+"\n","",0,null);
            if (command.contains("com.mylove.database.DatabaseBaselineMain") && !command.contains("AGENTSTUDIO_IMAGE_RECEIPT")) {
                try {
                    var sha = RemoteDatabaseSchemaResult.parse(schemaOutput.get(), objectMapper).get("schemaSha256");
                    var exit = baselineExitCode.get();
                    return new FixtureCommand(exit != null && exit == 0 ? "AGENTSTUDIO_BASELINE_REGISTERED=" + sha + "\n" : "BASELINE_NOT_CONFIRMED\n", "", exit, exit == null ? hangingDestroyed : null);
                } catch (Exception error) { throw new IllegalStateException(error); }
            }
            if (command.contains("schema-metadata-v1")) {
                var exit = schemaExitCode.get();
                return new FixtureCommand(exit != null && exit == 0 ? schemaOutput.get() : "",
                        exit != null && exit != 0 ? "mysql diagnostic failed\n" : "", exit,
                        exit == null ? hangingDestroyed : null);
            }
            if (command.contains("AGENTSTUDIO_IMAGE_RECEIPT")) {
                var matcher = java.util.regex.Pattern.compile("image='([^']+)'").matcher(command);
                if (!matcher.find()) throw new IllegalArgumentException("missing image");
                var receipt = "\nAGENTSTUDIO_IMAGE_RECEIPT\nRELEASE_ID=20260921T150000Z-cafebabe\nMANIFEST_SHA256="
                        + imageManifestSha.get() + "\nIMAGE_TAG=" + matcher.group(1)
                        + "\nIMAGE_ID=sha256:" + (imageBadReceipt.get() ? "invalid" : "c".repeat(64))
                        + "\nBUILDKIT_CONFIG_SHA256=" + ReleaseImageCommands.configSha256()
                        + "\nBUILDER_CLEANED=true\n";
                var exit = imageExitCode.get();
                return new FixtureCommand(exit != null && exit == 0
                        ? (imageLargeOutput.get() ? "x".repeat(30_000) : "build-ok") + receipt : "",
                        exit != null && exit != 0 ? imageFailureOutput.get() : "", exit,
                        exit == null ? hangingDestroyed : null);
            }
            if (command.startsWith("set -eu; test ! -L")) return new FixtureCommand("cleanup diagnostic\n", "", imageCleanupExitCode.get(), null);
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
    void mapsFixedCommandsWithoutReadingEnvironmentFile() {
        var commands = new RemoteDeploymentCommands();
        assertThat(commands.command("COMPOSE_VALIDATE", profile)).contains("config --quiet");
        assertThat(commands.command("COMPOSE_STATUS", profile)).contains("ps --format json 'nginx' 'app' 'mysql' 'phpmyadmin'");
        assertThat(commands.command("NGINX_VALIDATE", profile)).endsWith("exec -T nginx nginx -t");
        assertThat(commands.command("SITE_HEALTH", profile)).contains("http://127.0.0.1/");
        assertThat(commands.command("RELEASE_FINGERPRINT", profile)).contains("sha256sum", "stat", "app.jar");
        assertThat(commands.command("DATABASE_SCHEMA", profile)).contains("information_schema", "START TRANSACTION READ ONLY");
        for (var task : RemoteDeploymentCommands.TASKS) {
            assertThat(commands.command(task, profile)).doesNotContain("cat ");
            if (!task.equals("RELEASE_STATUS")) assertThat(commands.command(task, profile)).doesNotContain(".env");
        }
        // Only the aggregate digest is returned; never the protected file contents or its individual hash.
        assertThat(commands.command("RELEASE_STATUS", profile)).contains("done | sha256sum", "CURRENT_IMAGE_ID", "PRODUCTION_SHA256");
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
                .hasMessageContaining("固定部署诊断");
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree(
                "{\"task\":\"COMPOSE_STATUS\",\"command\":\"docker compose down\"}")))
                .hasMessageContaining("只接受固定 task");
        assertThat(receivedCommands).isEmpty();
    }

    @Test
    void schemaInspectionUsesExistingHighToolAndOnePinnedSessionWithoutReadingRows() throws Exception {
        var tool = tool(Duration.ofSeconds(5));
        assertThat(tool.descriptor().riskLevel()).isEqualTo("HIGH");
        var result = objectMapper.readTree(tool.execute(objectMapper.readTree("{\"task\":\"DATABASE_SCHEMA\"}")));
        assertThat(result.path("schemaComplete").asBoolean()).isTrue();
        assertThat(result.path("schemaSha256").asText()).matches("[0-9a-f]{64}");
        assertThat(result.path("databaseModified").asBoolean()).isFalse();
        assertThat(result.path("baselineRegistered").asBoolean()).isFalse();
        assertThat(result.path("output").asText()).doesNotContain("never-return-this");
        assertThat(sessionsCreated).hasValue(1);
        assertThat(receivedCommands).singleElement().asString().contains("exec -T mysql", "--protocol=socket", "MYSQL_PWD=");
    }

    @Test
    void failedTruncatedOrMalformedSchemaCannotIssueFingerprint() throws Exception {
        schemaExitCode.set(2);
        var failed = objectMapper.readTree(tool(Duration.ofSeconds(5)).execute(objectMapper.readTree("{\"task\":\"DATABASE_SCHEMA\"}")));
        assertThat(failed.path("successful").asBoolean()).isFalse();
        assertThat(failed.has("schemaSha256")).isFalse();
        schemaExitCode.set(0); schemaOutput.set("x".repeat(60_000));
        var truncated = objectMapper.readTree(tool(Duration.ofSeconds(5)).execute(objectMapper.readTree("{\"task\":\"DATABASE_SCHEMA\"}")));
        assertThat(truncated.path("outputTruncated").asBoolean()).isTrue();
        assertThat(truncated.path("schemaComplete").asBoolean()).isFalse();
        assertThat(truncated.has("schemaSha256")).isFalse();
        schemaOutput.set("[\"DATABASE\",\"fixture\",\"8.0\"]\n");
        assertThatThrownBy(() -> tool(Duration.ofSeconds(5)).execute(objectMapper.readTree("{\"task\":\"DATABASE_SCHEMA\"}")))
                .hasMessageContaining("完整边界");
    }

    @Test
    void schemaTimeoutClosesChannelAndSqlOverrideIsRejectedBeforeConnecting() throws Exception {
        var tool = tool(Duration.ofMillis(150));
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree("{\"task\":\"DATABASE_SCHEMA\",\"sql\":\"DROP TABLE users\"}")))
                .hasMessageContaining("只接受固定 task");
        assertThat(receivedCommands).isEmpty();
        schemaExitCode.set(null);
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree("{\"task\":\"DATABASE_SCHEMA\"}")))
                .hasMessageContaining("已关闭远程命令通道");
        awaitTrue(hangingDestroyed);
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

    @Test
    void imageBuildRegistersBoundCandidateAndReturnsUniqueVerifiedImageUsingOneSession() throws Exception {
        prepareImageFixture();
        var tool = imageTool(Duration.ofSeconds(5));
        var descriptor = new ToolRegistry(List.of(tool), objectMapper).descriptor("build_release_candidate_image");
        assertThat(descriptor.source()).isEqualTo("SSH");
        assertThat(descriptor.capability()).isEqualTo("EXECUTE");
        assertThat(descriptor.riskLevel()).isEqualTo("HIGH");
        assertThat(descriptor.inputSchema().get("additionalProperties")).isEqualTo(false);
        assertThat(tool.targetEnvironment()).contains("REGISTRY_MIRRORS:https://docker.1ms.run/",
                "https://docker.1panel.live/", "https://docker.ketches.cn/", ReleaseImageCommands.configSha256(),
                "LIMIT:512MiB,0.5CPU,900s", "REGISTRY_RETRY:3_MAX,SHARED_900s,5s_BACKOFF,TRANSPORT_ONLY");
        var result = objectMapper.readTree(tool.execute(imageArguments()));
        assertThat(result.path("successful").asBoolean()).isTrue();
        assertThat(result.path("imageId").asText()).isEqualTo("sha256:" + "c".repeat(64));
        assertThat(result.path("imageTag").asText()).matches("agentstudio-candidate:20260921T150000Z-cafebabe-[0-9a-f]{32}");
        assertThat(result.path("productionModified").asBoolean()).isFalse();
        assertThat(result.path("servicesRestarted").asBoolean()).isFalse();
        assertThat(result.path("registryMirrors").size()).isEqualTo(3);
        assertThat(result.path("buildkitConfigSha256").asText()).isEqualTo(ReleaseImageCommands.configSha256());
        assertThat(sessionsCreated).hasValue(1);
        assertThat(receivedCommands).hasSize(1);
        assertThat(receivedCommands.getFirst()).contains("memory=512m", "cpu-quota=50000", "--network none",
                "build_deadline=$(($(date +%s) + 900))", "timeout -k 10s \"${remaining}s\"",
                "sha256sum -c -", "flock -n", "cp -- app.jar Dockerfile");
        assertThat(receivedCommands.getFirst()).doesNotContain("compose up", "restart ", "prune", "--push", "--use ", "--allow");
    }

    @Test
    void imageNonZeroExitIsAuditableAndRequestsScopedCleanup() throws Exception {
        prepareImageFixture(); imageExitCode.set(17);
        var result = objectMapper.readTree(imageTool(Duration.ofSeconds(5)).execute(imageArguments()));
        assertThat(result.path("successful").asBoolean()).isFalse();
        assertThat(result.path("exitCode").asInt()).isEqualTo(17);
        assertThat(result.path("output").asText()).contains("build failed");
        assertThat(receivedCommands).hasSize(2);
        assertThat(receivedCommands.getLast()).contains("builder.created", "docker buildx rm --force 'agentstudio-");
        assertThat(sessionsCreated).hasValue(1);
    }

    @Test
    void imageCleanupFailureDoesNotHideOriginalBuildExitOrOutput() throws Exception {
        prepareImageFixture(); imageExitCode.set(17); imageCleanupExitCode.set(18);
        imageFailureOutput.set("AGENTSTUDIO_STAGE=IMAGE_BUILD\noriginal build error\n");
        assertThatThrownBy(() -> imageTool(Duration.ofSeconds(5)).execute(imageArguments()))
                .hasMessageContaining("stage=IMAGE_BUILD, exitCode=17")
                .hasMessageContaining("original build error").hasMessageContaining("清理未确认：exitCode=18")
                .hasMessageContaining("cleanup diagnostic").hasMessageContaining("attempt=");
    }

    @Test
    void imageBootstrapTimeoutRetainsPhaseAndRegistryDiagnosticAfterSuccessfulCleanup() throws Exception {
        prepareImageFixture(); imageExitCode.set(124);
        imageFailureOutput.set("AGENTSTUDIO_STAGE=BUILDER_BOOTSTRAP\nregistry download timeout\n");
        assertThatThrownBy(() -> imageTool(Duration.ofSeconds(5)).execute(imageArguments()))
                .hasMessageContaining("stage=BUILDER_BOOTSTRAP, exitCode=124")
                .hasMessageContaining("registry download timeout").hasMessageContaining("清理已确认");
    }

    @Test
    void imageTimeoutClosesChannelAndCleansOnlyItsDedicatedBuilder() throws Exception {
        prepareImageFixture(); imageExitCode.set(null);
        assertThatThrownBy(() -> imageTool(Duration.ofMillis(150)).execute(imageArguments()))
                .hasMessageContaining("超时").hasMessageContaining("已关闭远程命令通道");
        awaitTrue(hangingDestroyed);
        assertThat(receivedCommands).hasSize(2);
        assertThat(receivedCommands.getLast()).contains("builder.created").doesNotContain("--all", "prune");
    }

    @Test
    void imageTruncationPreservesTailReceiptAndRejectsForgedReceipt() throws Exception {
        prepareImageFixture(); imageLargeOutput.set(true);
        var result = objectMapper.readTree(imageTool(Duration.ofSeconds(5)).execute(imageArguments()));
        assertThat(result.path("successful").asBoolean()).isTrue();
        assertThat(result.path("outputTruncated").asBoolean()).isTrue();
        assertThat(result.path("output").asText()).hasSizeLessThan(16_100).contains("AGENTSTUDIO_IMAGE_RECEIPT");
        imageBadReceipt.set(true);
        assertThatThrownBy(() -> imageTool(Duration.ofSeconds(5)).execute(imageArguments()))
                .hasMessageContaining("回执校验失败");
    }

    @Test
    void imageRejectsChangedManifestAndMissingCandidateBeforeExec() throws Exception {
        prepareImageFixture();
        var arguments = imageArguments();
        Files.writeString(root.resolve("srv/old-things-releases/20260921T150000Z-cafebabe/manifest.properties"), "changed");
        assertThatThrownBy(() -> imageTool(Duration.ofSeconds(5)).execute(arguments)).hasMessageContaining("摘要不匹配");
        var missing = objectMapper.readTree("{\"releaseId\":\"20260921T150000Z-deadbeef\",\"manifestSha256\":\"" + "a".repeat(64) + "\"}");
        assertThatThrownBy(() -> imageTool(Duration.ofSeconds(5)).execute(missing));
        assertThat(receivedCommands).isEmpty();
    }

    @Test
    void imageRejectsDangerousParametersBeforeConnecting() throws Exception {
        var tool = imageTool(Duration.ofSeconds(5));
        for (var json : List.of("{}", "{\"releaseId\":\"../x\",\"manifestSha256\":\"" + "a".repeat(64) + "\"}",
                "{\"releaseId\":\"20260921T150000Z-cafebabe\",\"manifestSha256\":\"" + "a".repeat(64) + "\",\"command\":\"reboot\"}",
                "{\"releaseId\":\"20260921T150000Z-cafebabe\",\"manifestSha256\":\"" + "a".repeat(64) + "\",\"registryMirrors\":[\"http://evil.invalid\"]}")) {
            assertThatThrownBy(() -> tool.execute(objectMapper.readTree(json))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(sessionsCreated).hasValue(0);
    }

    @Test
    void imageManifestMustBindEveryFixedFileAndCannotReuseOldFormat() {
        var valid = imageManifest();
        assertThat(BuildReleaseCandidateImageTool.parseManifest(valid.getBytes(StandardCharsets.UTF_8), "20260921T150000Z-cafebabe"))
                .containsKey("dockerfileSha256");
        assertThatThrownBy(() -> BuildReleaseCandidateImageTool.parseManifest(valid.replace("candidateFormat=2", "candidateFormat=1")
                .getBytes(StandardCharsets.UTF_8), "20260921T150000Z-cafebabe")).hasMessageContaining("重新准备候选");
        assertThatThrownBy(() -> BuildReleaseCandidateImageTool.parseManifest((valid + "releaseId=evil\n")
                .getBytes(StandardCharsets.UTF_8), "20260921T150000Z-cafebabe")).hasMessageContaining("重复");
        assertThatThrownBy(() -> BuildReleaseCandidateImageTool.parseManifest(valid.replace("dockerfileSha256=" + "a".repeat(64) + "\n", "")
                .getBytes(StandardCharsets.UTF_8), "20260921T150000Z-cafebabe")).hasMessageContaining("缺少固定文件摘要");
    }

    @Test
    void imageCancellationClosesChannelAndStillAttemptsCleanup() throws Exception {
        prepareImageFixture(); imageExitCode.set(null);
        var failure = new AtomicReference<Throwable>();
        var worker = new Thread(() -> {
            try { imageTool(Duration.ofSeconds(5)).execute(imageArguments()); }
            catch (Throwable exception) { failure.set(exception); }
        });
        worker.start();
        try {
            var deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
            while (System.nanoTime() < deadline) {
                synchronized (receivedCommands) { if (!receivedCommands.isEmpty()) break; }
                Thread.sleep(10);
            }
            // MINA channel open/close may still be completing at the cancellation boundary;
            // allow the configured 5-second connection budget plus bounded cleanup.
            worker.interrupt(); worker.join(15000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isNotNull();
            awaitTrue(hangingDestroyed);
            assertThat(receivedCommands).hasSize(2);
            assertThat(receivedCommands.getLast()).contains("builder.created", "docker buildx rm --force");
        } finally { worker.interrupt(); worker.join(1000); }
    }

    @Test
    void imageCandidateRejectsSymlinkAncestorsDirectoryAndEveryFixedFile() throws Exception {
        var candidate = "/srv/old-things-releases/20260921T150000Z-cafebabe";
        var unsafe = new ArrayList<>(List.of("/srv", "/srv/old-things-releases", candidate));
        for (var name : List.of("app.jar", "Dockerfile", "compose.yml", "nginx.conf", "manifest.properties", "SHA256SUMS"))
            unsafe.add(candidate + "/" + name);
        for (var linked : unsafe) {
            var sftp = mock(org.apache.sshd.sftp.client.SftpClient.class);
            when(sftp.lstat(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
                var path = invocation.<String>getArgument(0);
                var attributes = new org.apache.sshd.sftp.client.SftpClient.Attributes();
                attributes.perms(path.equals(linked) ? org.apache.sshd.sftp.common.SftpConstants.S_IFLNK
                        : path.length() <= candidate.length() ? org.apache.sshd.sftp.common.SftpConstants.S_IFDIR
                        : org.apache.sshd.sftp.common.SftpConstants.S_IFREG);
                return attributes;
            });
            assertThatThrownBy(() -> new RemoteDeploymentWorkspace.Access(sftp, profile)
                    .validateCandidate("20260921T150000Z-cafebabe"))
                    .hasMessageContaining("符号链接");
        }
    }

    private String imageManifest() {
        return "candidateFormat=2\nreleaseId=20260921T150000Z-cafebabe\nartifactPath=target/app.jar\n"
                + "artifactSha256=" + "a".repeat(64) + "\ndockerfileSha256=" + "a".repeat(64)
                + "\ncomposeSha256=" + "a".repeat(64) + "\nnginxSha256=" + "a".repeat(64)
                + "\nproductionModified=false\nfiles=app.jar,Dockerfile,compose.yml,nginx.conf\n";
    }

    private com.fasterxml.jackson.databind.node.ObjectNode baselineArguments() throws Exception {
        prepareImageFixture();
        var backup = root.resolve("srv/backups/20260921T120000Z-deadbeef"); Files.createDirectories(backup);
        for (var name : List.of("database.sql", "uploads.tar.gz", "app.jar", "Dockerfile", "compose.yml", "nginx.conf", ".env", "images.json", "services.json", "SHA256SUMS", "manifest.sha256")) Files.writeString(backup.resolve(name), "fixture");
        var manifest = "backupFormat=1\nbackupId=20260921T120000Z-deadbeef\ncreatedAt=" + Instant.now() + "\ndeploymentRoot=/srv/old-things\ncomposeProject=old-things\n";
        Files.writeString(backup.resolve("manifest.properties"), manifest);
        var result = objectMapper.createObjectNode();
        result.put("releaseId", "20260921T150000Z-cafebabe"); result.put("manifestSha256", imageManifestSha.get());
        result.put("imageId", "sha256:" + "c".repeat(64));
        result.put("schemaSha256", RemoteDatabaseSchemaResult.parse(schemaOutput.get(), objectMapper).get("schemaSha256").toString());
        result.put("backupId", "20260921T120000Z-deadbeef");
        result.put("backupManifestSha256", RemoteReleaseCandidateStager.sha256(manifest.getBytes(StandardCharsets.UTF_8)));
        return result;
    }

    private AdoptRemoteDatabaseBaselineTool baselineTool(Duration timeout) {
        var secrets = mock(SecretResolver.class); when(secrets.resolve("TEST_SSH_PASSWORD")).thenReturn(Optional.of("password"));
        var sshService = mock(SshWorkspaceService.class); when(sshService.current()).thenReturn(ssh);
        var profiles = mock(RemoteDeploymentService.class); when(profiles.current()).thenReturn(profile);
        when(profiles.target(profile)).thenReturn("fixture-target"); when(profiles.approvalTarget()).thenReturn("pinned-fixture");
        return new AdoptRemoteDatabaseBaselineTool(new RemoteDeploymentWorkspace(new SftpSessionFactory(secrets), sshService), profiles, objectMapper, timeout);
    }

    @Test void baselineRegistersHighBoundToolAndUsesSameSessionForRecheckAndMaintenance() throws Exception {
        var args = baselineArguments(); var tool = baselineTool(Duration.ofSeconds(5));
        var descriptor = new ToolRegistry(List.of(tool), objectMapper).descriptor("adopt_remote_database_baseline");
        assertThat(descriptor.source()).isEqualTo("SSH"); assertThat(descriptor.riskLevel()).isEqualTo("HIGH");
        assertThat(descriptor.capability()).isEqualTo("WRITE");
        var result = objectMapper.readTree(tool.execute(args));
        assertThat(result.path("baselineRegistered").asBoolean()).isTrue();
        assertThat(result.path("servicesRestarted").asBoolean()).isFalse();
        assertThat(sessionsCreated).hasValue(1); assertThat(receivedCommands).hasSize(2);
        assertThat(receivedCommands.getLast()).contains("--pull=never", "--read-only", "--cap-drop ALL", "flock -n", "age\" -le 1800");
        assertThat(receivedCommands.getLast()).doesNotContain("compose up", "system prune", "mysqldump", "never-return-this");
    }

    @Test void baselineRejectsSqlAndSchemaDriftBeforeMaintenanceExec() throws Exception {
        var args = baselineArguments(); var tool = baselineTool(Duration.ofSeconds(5));
        args.put("sql", "DROP TABLE users"); assertThatThrownBy(() -> tool.execute(args)).isInstanceOf(IllegalArgumentException.class);
        assertThat(receivedCommands).isEmpty(); args.remove("sql"); args.put("schemaSha256", "0".repeat(64));
        assertThatThrownBy(() -> tool.execute(args)).hasMessageContaining("结构已变化");
        assertThat(receivedCommands).hasSize(1);
    }

    @Test void baselineNonzeroIsAuditableUnknownStateAndTimeoutClosesChannel() throws Exception {
        var args = baselineArguments(); baselineExitCode.set(1);
        var failed = objectMapper.readTree(baselineTool(Duration.ofSeconds(5)).execute(args));
        assertThat(failed.path("successful").asBoolean()).isFalse();
        assertThat(failed.path("databaseHistoryMayHaveChanged").asBoolean()).isTrue();
        baselineExitCode.set(null);
        assertThatThrownBy(() -> baselineTool(Duration.ofMillis(150)).execute(args)).hasMessageContaining("状态未确认");
        awaitTrue(hangingDestroyed);
    }

    @Test void baselineRejectsOldBackupAndManifestForgeryWithoutExec() throws Exception {
        var args = baselineArguments(); args.put("backupManifestSha256", "0".repeat(64));
        assertThatThrownBy(() -> baselineTool(Duration.ofSeconds(5)).execute(args)).hasMessageContaining("备份摘要");
        assertThat(receivedCommands).isEmpty();
        var freshArgs = baselineArguments();
        var backup = root.resolve("srv/backups/20260921T120000Z-deadbeef/manifest.properties");
        var old = Files.readString(backup).replaceFirst("createdAt=[^\\n]+", "createdAt=2020-01-01T00:00:00Z"); Files.writeString(backup, old);
        freshArgs.put("backupManifestSha256", RemoteReleaseCandidateStager.sha256(old.getBytes(StandardCharsets.UTF_8)));
        var expiredArgs = freshArgs;
        assertThatThrownBy(() -> baselineTool(Duration.ofSeconds(5)).execute(expiredArgs)).hasMessageContaining("新备份");
        assertThat(receivedCommands).isEmpty();
    }

    private PublishRemoteReleaseTool publishTool(Duration timeout) {
        var secrets=mock(SecretResolver.class); when(secrets.resolve("TEST_SSH_PASSWORD")).thenReturn(Optional.of("password"));
        var sshService=mock(SshWorkspaceService.class); when(sshService.current()).thenReturn(ssh);
        var profiles=mock(RemoteDeploymentService.class); when(profiles.current()).thenReturn(profile);
        when(profiles.target(profile)).thenReturn("fixture-target"); when(profiles.approvalTarget()).thenReturn("pinned-fixture");
        return new PublishRemoteReleaseTool(new RemoteDeploymentWorkspace(new SftpSessionFactory(secrets),sshService),profiles,objectMapper,timeout);
    }
    private com.fasterxml.jackson.databind.node.ObjectNode publishArguments() throws Exception {
        var args=baselineArguments(); args.put("previousImageId","sha256:"+"b".repeat(64)); args.put("productionSha256","a".repeat(64)); return args;
    }
    @Test void publishUsesPinnedSameSessionAndUploadsExclusiveFixedScript() throws Exception {
        var args=publishArguments(); var tool=publishTool(Duration.ofSeconds(5));
        assertThat(tool.descriptor().riskLevel()).isEqualTo("HIGH"); assertThat(tool.descriptor().capability()).isEqualTo("EXECUTE");
        var result=objectMapper.readTree(tool.execute(args));
        assertThat(result.path("deployed").asBoolean()).isTrue(); assertThat(result.path("successful").asBoolean()).isTrue();
        assertThat(result.path("currentImageId").asText()).isEqualTo(args.path("imageId").asText());
        assertThat(sessionsCreated).hasValue(1); assertThat(receivedCommands).hasSize(1);
        var path=root.resolve(result.path("attemptPath").asText().substring(1)).resolve("publish.sh");
        assertThat(Files.readString(path)).contains("--no-deps --no-build --pull never", "DatabaseReleaseMain", "flock -n", "receipt.properties").doesNotContain("never-return-this");
    }
    @Test void publishRollbackIsNotSuccessAndTimeoutNeverClaimsRecovery() throws Exception {
        var args=publishArguments(); publishExitCode.set(47);
        var result=objectMapper.readTree(publishTool(Duration.ofSeconds(5)).execute(args));
        assertThat(result.path("successful").asBoolean()).isFalse(); assertThat(result.path("rolledBack").asBoolean()).isTrue();
        assertThat(result.path("currentImageId").asText()).isEqualTo(args.path("previousImageId").asText());
        publishExitCode.set(null);
        assertThatThrownBy(() -> publishTool(Duration.ofMillis(150)).execute(args)).hasMessageContaining("停止重试");
        awaitTrue(hangingDestroyed);
    }
    @Test void publishRejectsExtraOptionsAndForgeryBeforeRemoteExecution() throws Exception {
        var args=publishArguments(); args.put("command","docker compose down");
        assertThatThrownBy(() -> publishTool(Duration.ofSeconds(5)).execute(args)).isInstanceOf(IllegalArgumentException.class);
        args.remove("command"); args.put("manifestSha256","0".repeat(64));
        assertThatThrownBy(() -> publishTool(Duration.ofSeconds(5)).execute(args)).hasMessageContaining("候选摘要");
        assertThat(receivedCommands).isEmpty();
    }
    @Test void releaseStatusReturnsOnlyBoundedImmutableProductionIdentity() throws Exception {
        var result=objectMapper.readTree(tool(Duration.ofSeconds(5)).execute(objectMapper.readTree("{\"task\":\"RELEASE_STATUS\"}")));
        assertThat(result.path("successful").asBoolean()).isTrue(); assertThat(result.path("appHealth").asText()).isEqualTo("healthy");
        assertThat(result.path("currentImageId").asText()).isEqualTo("sha256:"+"b".repeat(64));
        assertThat(result.path("productionSha256").asText()).isEqualTo("a".repeat(64));
        assertThat(result.toString()).doesNotContain("never-return-this");
    }

    private void prepareImageFixture() throws Exception {
        var candidate = root.resolve("srv/old-things-releases/20260921T150000Z-cafebabe");
        Files.createDirectories(candidate);
        for (var file : List.of("app.jar", "Dockerfile", "compose.yml", "nginx.conf", "SHA256SUMS"))
            Files.writeString(candidate.resolve(file), "fixture");
        Files.writeString(candidate.resolve("manifest.properties"), imageManifest());
        imageManifestSha.set(RemoteReleaseCandidateStager.sha256(imageManifest().getBytes(StandardCharsets.UTF_8)));
    }

    private com.fasterxml.jackson.databind.JsonNode imageArguments() throws Exception {
        return objectMapper.readTree("{\"releaseId\":\"20260921T150000Z-cafebabe\",\"manifestSha256\":\"" + imageManifestSha.get() + "\"}");
    }

    private BuildReleaseCandidateImageTool imageTool(Duration timeout) {
        var secrets = mock(SecretResolver.class);
        when(secrets.resolve("TEST_SSH_PASSWORD")).thenReturn(Optional.of("password"));
        var sshService = mock(SshWorkspaceService.class); when(sshService.current()).thenReturn(ssh);
        var workspace = new RemoteDeploymentWorkspace(new SftpSessionFactory(secrets), sshService);
        var profiles = mock(RemoteDeploymentService.class);
        when(profiles.current()).thenReturn(profile);
        when(profiles.target(profile)).thenReturn("tester@127.0.0.1:" + server.getPort() + "/srv/old-things");
        when(profiles.approvalTarget()).thenReturn(ssh.approvalTarget() + "|DEPLOY:/srv/old-things");
        return new BuildReleaseCandidateImageTool(workspace, profiles, objectMapper, timeout);
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

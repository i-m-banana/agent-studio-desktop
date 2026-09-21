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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
                "compose.yml", "old-things", "nginx.conf", "http://127.0.0.1/",
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
        assertThat(hangingDestroyed).isTrue();
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
}

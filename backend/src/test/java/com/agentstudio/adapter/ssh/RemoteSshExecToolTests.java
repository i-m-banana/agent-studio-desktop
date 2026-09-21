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
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteSshExecToolTests {
    @TempDir Path root;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> receivedCommands = new ArrayList<>();
    private final AtomicInteger sessionsCreated = new AtomicInteger();
    private final AtomicBoolean hangingCommandDestroyed = new AtomicBoolean();
    private SshServer server;
    private SshWorkspaceProperties properties;

    @BeforeEach
    void startServerWithSftpAndExecChannels() throws Exception {
        Files.createDirectories(root.resolve("workspace/git-project/.git"));
        Files.createDirectories(root.resolve("workspace/maven-project"));
        Files.createDirectories(root.resolve("workspace/npm-project"));
        Files.createDirectories(root.resolve("workspace/slow-project"));
        Files.createDirectories(root.resolve("workspace/unsafe;project"));
        Files.writeString(root.resolve("workspace/maven-project/pom.xml"), "<project/>");
        Files.writeString(root.resolve("workspace/npm-project/package.json"), "{}");
        Files.writeString(root.resolve("workspace/slow-project/package.json"), "{}");
        Files.writeString(root.resolve("workspace/unsafe;project/package.json"), "{}");

        server = SshServer.setUpDefaultServer();
        server.setPort(0);
        var keys = new SimpleGeneratorHostKeyProvider(root.resolve("host-key.ser"));
        server.setKeyPairProvider(keys);
        server.setPasswordAuthenticator((username, password, session) ->
                username.equals("tester") && password.equals("password"));
        server.setFileSystemFactory(new VirtualFileSystemFactory(root));
        SftpModuleProperties.SFTP_VERSION.set(server, 3);
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
        server.setCommandFactory((channel, command) -> {
            synchronized (receivedCommands) { receivedCommands.add(command); }
            if (command.contains("slow-project")) return new FixtureCommand(null, null, null, hangingCommandDestroyed);
            if (command.contains("npm test")) return new FixtureCommand("test-failed\n", "stderr-detail\n", 7, null);
            if (command.contains("npm run build")) return new FixtureCommand("x".repeat(20_000), "", 0, null);
            return new FixtureCommand("task-ok\n", "", 0, null);
        });
        server.addSessionListener(new SessionListener() {
            @Override public void sessionCreated(Session session) { sessionsCreated.incrementAndGet(); }
        });
        server.start();
        var hostKey = keys.loadKeys(null).iterator().next().getPublic();
        properties = new SshWorkspaceProperties("127.0.0.1", server.getPort(), "tester", "/workspace",
                KeyUtils.getFingerPrint(BuiltinDigests.sha256, hostKey),
                "TEST_SSH_PASSWORD", Duration.ofSeconds(5));
    }

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) server.stop(true);
    }

    @Test
    void registersExecuteHighToolAndUsesOneSessionForSftpValidationAndExec() throws Exception {
        var tool = tool(Duration.ofSeconds(5));
        var registry = new ToolRegistry(List.of(tool), objectMapper);
        var descriptor = registry.descriptor("run_remote_workspace_task");

        assertThat(descriptor.source()).isEqualTo("SSH");
        assertThat(descriptor.capability()).isEqualTo("EXECUTE");
        assertThat(descriptor.riskLevel()).isEqualTo("HIGH");
        assertThat(descriptor.inputSchema().toString()).doesNotContain("command", "arguments", "environment");
        assertThat(registry.targetEnvironment(descriptor.name())).isEqualTo("SSH:" + properties.approvalTarget());

        var result = objectMapper.readTree(tool.execute(objectMapper.readTree(
                "{\"path\":\"git-project\",\"task\":\"GIT_STATUS\"}")));

        assertThat(result.path("task").asText()).isEqualTo("GIT_STATUS");
        assertThat(result.path("target").asText()).isEqualTo(properties.target());
        assertThat(result.path("path").asText()).isEqualTo("git-project");
        assertThat(result.path("successful").asBoolean()).isTrue();
        assertThat(result.path("exitCode").asInt()).isZero();
        assertThat(result.path("output").asText()).contains("task-ok");
        assertThat(result.path("outputTruncated").asBoolean()).isFalse();
        assertThat(sessionsCreated).hasValue(1);
    }

    @Test
    void mapsOnlyFiveFixedCommands() {
        var commands = new RemoteExecCommands();
        assertThat(commands.command("GIT_STATUS", "/workspace/project"))
                .isEqualTo("cd '/workspace/project' && git --no-pager status --short --branch --untracked-files=normal");
        assertThat(commands.command("GIT_DIFF_SUMMARY", "/workspace/project"))
                .isEqualTo("cd '/workspace/project' && git --no-pager diff --stat HEAD -- .");
        assertThat(commands.command("MAVEN_TEST", "/workspace/project"))
                .isEqualTo("cd '/workspace/project' && CI=true NO_COLOR=1 mvn --batch-mode --no-transfer-progress test");
        assertThat(commands.command("NPM_TEST", "/workspace/project"))
                .isEqualTo("cd '/workspace/project' && CI=true NO_COLOR=1 npm test");
        assertThat(commands.command("NPM_BUILD", "/workspace/project"))
                .isEqualTo("cd '/workspace/project' && CI=true NO_COLOR=1 npm run build");
        assertThatThrownBy(() -> commands.command("SHELL", "/workspace/project"))
                .hasMessageContaining("task 只允许");
        assertThatThrownBy(() -> commands.validateRelativePath("project; whoami"))
                .hasMessageContaining("不安全字符");
        assertThatThrownBy(() -> new RemotePathPolicy("/workspace/../outside"))
                .hasMessageContaining("不安全的路径分量");
    }

    @Test
    void returnsNonZeroExitAndMergedOutputAsAuditableResult() throws Exception {
        var result = objectMapper.readTree(tool(Duration.ofSeconds(5)).execute(objectMapper.readTree(
                "{\"path\":\"npm-project\",\"task\":\"NPM_TEST\"}")));

        assertThat(result.path("successful").asBoolean()).isFalse();
        assertThat(result.path("exitCode").asInt()).isEqualTo(7);
        assertThat(result.path("output").asText()).contains("test-failed", "stderr-detail");
    }

    @Test
    void truncatesLargeOutputWithoutFailingSuccessfulCommand() throws Exception {
        var result = objectMapper.readTree(tool(Duration.ofSeconds(5)).execute(objectMapper.readTree(
                "{\"path\":\"npm-project\",\"task\":\"NPM_BUILD\"}")));

        assertThat(result.path("successful").asBoolean()).isTrue();
        assertThat(result.path("outputTruncated").asBoolean()).isTrue();
        assertThat(result.path("output").asText()).contains("输出已截断").hasSizeLessThan(16_100);
    }

    @Test
    void closesExecChannelWhenCommandTimesOut() {
        assertThatThrownBy(() -> tool(Duration.ofMillis(150)).execute(objectMapper.readTree(
                "{\"path\":\"slow-project\",\"task\":\"NPM_BUILD\"}")))
                .hasMessageContaining("已关闭远程命令通道");
        assertThat(hangingCommandDestroyed).isTrue();
    }

    @Test
    void rejectsTraversalUnsafeDirectoryMissingMarkerAndDangerousParameters() throws Exception {
        var tool = tool(Duration.ofSeconds(5));
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree(
                "{\"path\":\"../outside\",\"task\":\"NPM_BUILD\"}")))
                .hasMessageContaining("不能越出");
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree(
                "{\"path\":\"unsafe;project\",\"task\":\"NPM_BUILD\"}")))
                .hasMessageContaining("不安全字符");
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree(
                "{\"path\":\"maven-project\",\"task\":\"NPM_BUILD\"}")))
                .hasMessageContaining("package.json");
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree(
                "{\"path\":\"npm-project\",\"task\":\"SHELL\"}")))
                .hasMessageContaining("不支持");
        assertThat(receivedCommands).isEmpty();
    }

    @Test
    void rejectsSymbolicLinkWorkingDirectoryDeterministically() throws Exception {
        var sftp = mock(SftpClient.class);
        var directory = new SftpClient.Attributes();
        directory.setType(SftpConstants.SSH_FILEXFER_TYPE_DIRECTORY);
        var symlink = new SftpClient.Attributes();
        symlink.setType(SftpConstants.SSH_FILEXFER_TYPE_SYMLINK);
        symlink.perms(SftpConstants.S_IFLNK);
        when(sftp.lstat("/workspace")).thenReturn(directory);
        when(sftp.lstat("/workspace/link-project")).thenReturn(symlink);
        var secrets = mock(SecretResolver.class);
        var workspace = new RemoteSftpWorkspace(new SftpSessionFactory(secrets), properties);
        var access = workspace.new Access(sftp, new RemotePathPolicy("/workspace"));

        assertThatThrownBy(() -> access.requireDirectory("link-project"))
                .hasMessageContaining("符号链接");
    }

    private RunRemoteWorkspaceTaskTool tool(Duration timeout) {
        var secrets = mock(SecretResolver.class);
        when(secrets.resolve("TEST_SSH_PASSWORD")).thenReturn(Optional.of("password"));
        var workspace = new RemoteSftpWorkspace(new SftpSessionFactory(secrets), properties);
        return new RunRemoteWorkspaceTaskTool(workspace, objectMapper, new RemoteExecCommands(), timeout);
    }

    private static final class FixtureCommand implements Command {
        private final String stdoutText;
        private final String stderrText;
        private final Integer exitCode;
        private final AtomicBoolean destroyed;
        private InputStream input;
        private OutputStream output;
        private OutputStream error;
        private ExitCallback callback;

        private FixtureCommand(String stdoutText, String stderrText, Integer exitCode, AtomicBoolean destroyed) {
            this.stdoutText = stdoutText;
            this.stderrText = stderrText;
            this.exitCode = exitCode;
            this.destroyed = destroyed;
        }

        @Override public void setInputStream(InputStream input) { this.input = input; }
        @Override public void setOutputStream(OutputStream output) { this.output = output; }
        @Override public void setErrorStream(OutputStream error) { this.error = error; }
        @Override public void setExitCallback(ExitCallback callback) { this.callback = callback; }

        @Override
        public void start(ChannelSession channel, Environment environment) throws IOException {
            if (exitCode == null) return;
            if (stdoutText != null) output.write(stdoutText.getBytes(StandardCharsets.UTF_8));
            if (stderrText != null) error.write(stderrText.getBytes(StandardCharsets.UTF_8));
            output.flush();
            error.flush();
            callback.onExit(exitCode);
        }

        @Override
        public void destroy(ChannelSession channel) throws Exception {
            if (destroyed != null) destroyed.set(true);
            if (input != null) input.close();
        }
    }
}

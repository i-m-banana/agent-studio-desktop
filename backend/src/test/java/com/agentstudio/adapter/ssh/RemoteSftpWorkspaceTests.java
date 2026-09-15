package com.agentstudio.adapter.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.agentstudio.secret.SecretResolver;
import com.agentstudio.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.SftpModuleProperties;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteSftpWorkspaceTests {
    @TempDir Path root;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private SshServer server;
    private SshWorkspaceProperties properties;

    @BeforeEach
    void startSftpServer() throws Exception {
        Files.createDirectories(root.resolve("workspace/src"));
        Files.createDirectories(root.resolve("workspace/node_modules/pkg"));
        Files.writeString(root.resolve("workspace/src/App.java"), "一\n二\n三", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("workspace/README.md"), "read me", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("workspace/src/Duplicate.txt"), "same\nsame", StandardCharsets.UTF_8);
        Files.write(root.resolve("workspace/src/Binary.bin"), new byte[] { 'a', 0, 'b' });
        Files.writeString(root.resolve("workspace/.env"), "TOKEN=hidden", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("workspace/node_modules/pkg/App.js"), "generated", StandardCharsets.UTF_8);
        server = SshServer.setUpDefaultServer(); server.setPort(0);
        var keys = new SimpleGeneratorHostKeyProvider(root.resolve("host-key.ser"));
        server.setKeyPairProvider(keys);
        server.setPasswordAuthenticator((username, password, session) ->
                username.equals("tester") && password.equals("password"));
        server.setFileSystemFactory(new VirtualFileSystemFactory(root));
        // Production targets OpenSSH, whose SFTP server deliberately negotiates protocol v3.
        SftpModuleProperties.SFTP_VERSION.set(server, 3);
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
        server.start();
        var hostKey = keys.loadKeys(null).iterator().next().getPublic();
        var fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, hostKey);
        properties = new SshWorkspaceProperties("127.0.0.1", server.getPort(), "tester", "/workspace",
                fingerprint, "TEST_SSH_PASSWORD", Duration.ofSeconds(5));
    }

    @AfterEach void stopSftpServer() throws Exception { if (server != null) server.stop(true); }

    @Test
    void registersAndExecutesReadOnlySftpToolsAgainstRealServer() throws Exception {
        var tools = tools(properties);
        var registry = new ToolRegistry(tools, objectMapper);
        assertThat(registry.descriptors()).extracting(item -> item.name()).containsExactlyInAnyOrder(
                "list_remote_workspace_directory", "search_remote_workspace_files",
                "read_remote_workspace_text_file", "apply_remote_workspace_text_patch");
        assertThat(registry.descriptor("read_remote_workspace_text_file")).satisfies(item -> {
            assertThat(item.source()).isEqualTo("SSH"); assertThat(item.capability()).isEqualTo("READ");
            assertThat(item.riskLevel()).isEqualTo("LOW");
        });
        assertThat(registry.descriptor("apply_remote_workspace_text_patch")).satisfies(item -> {
            assertThat(item.source()).isEqualTo("SSH"); assertThat(item.capability()).isEqualTo("WRITE");
            assertThat(item.riskLevel()).isEqualTo("HIGH");
        });
        assertThat(registry.targetEnvironment("read_remote_workspace_text_file"))
                .startsWith("SSH:tester@127.0.0.1:").endsWith("/workspace");
        var listing = objectMapper.readTree(tools.get(0).execute(objectMapper.readTree("{}")));
        assertThat(listing.path("entries").toString()).contains("src", "README.md").doesNotContain(".env");
        var search = objectMapper.readTree(tools.get(1).execute(objectMapper.readTree("{\"query\":\"app\"}")));
        assertThat(search.path("files").toString()).contains("src/App.java").doesNotContain("node_modules");
        var read = objectMapper.readTree(tools.get(2).execute(objectMapper.readTree(
                "{\"path\":\"src/App.java\",\"startLine\":2,\"maxLines\":1}")));
        assertThat(read.path("content").asText()).isEqualTo("二");
        assertThat(read.path("sha256").asText()).hasSize(64);
        assertThat(read.path("target").asText()).contains("tester@127.0.0.1");
    }

    @Test
    void appliesDigestBoundRemotePatchAndPreservesPermissions() throws Exception {
        var workspace = workspace(properties);
        var patchTool = new ApplyRemoteTextPatchTool(workspace, objectMapper);
        var file = root.resolve("workspace/src/App.java");
        var originalPermissions = workspace.execute(access ->
                access.permissions(access.requireFile("src/App.java")));
        var before = RemoteSftpWorkspace.sha256(Files.readAllBytes(file));
        var result = objectMapper.readTree(patchTool.execute(objectMapper.readTree("""
                {"path":"src/App.java","expectedSha256":"%s","replacements":[{"oldText":"二","newText":"two"}]}
                """.formatted(before))));

        assertThat(result.path("updated").asBoolean()).isTrue();
        assertThat(result.path("beforeSha256").asText()).isEqualTo(before);
        assertThat(result.path("afterSha256").asText()).hasSize(64).isNotEqualTo(before);
        int negotiatedVersion = workspace.execute(access -> access.sftpVersion());
        boolean posixRenameSupported = workspace.execute(access -> access.supportsPosixRename());
        assertThat(negotiatedVersion).isEqualTo(3);
        assertThat(posixRenameSupported).isTrue();
        assertThat(Files.readString(file)).isEqualTo("一\ntwo\n三");
        int updatedPermissions = workspace.execute(access ->
                access.permissions(access.requireFile("src/App.java")));
        assertThat(updatedPermissions).isEqualTo(originalPermissions);
        try (var siblings = Files.list(file.getParent())) {
            assertThat(siblings.map(Path::getFileName).map(Path::toString))
                    .noneMatch(name -> name.startsWith(".agent-studio-patch-"));
        }
    }

    @Test
    void rejectsUnsafeRemotePatchesWithoutChangingFile() throws Exception {
        var patchTool = tools(properties).get(3);
        var file = root.resolve("workspace/src/App.java");
        var original = Files.readAllBytes(file);
        var sha = RemoteSftpWorkspace.sha256(original);

        assertThatThrownBy(() -> patchTool.execute(objectMapper.readTree("""
                {"path":"src/App.java","expectedSha256":"%s","replacements":[{"oldText":"missing","newText":"x"}]}
                """.formatted(sha)))).hasMessageContaining("不存在");
        assertThatThrownBy(() -> patchTool.execute(objectMapper.readTree("""
                {"path":"src/App.java","expectedSha256":"%s","replacements":[{"oldText":"一","newText":"x"}]}
                """.formatted("0".repeat(64))))).hasMessageContaining("内容已变化");
        assertThatThrownBy(() -> patchTool.execute(objectMapper.readTree("""
                {"path":"../outside","expectedSha256":"%s","replacements":[{"oldText":"一","newText":"x"}]}
                """.formatted(sha)))).hasMessageContaining("越出远程工作区");
        assertThatThrownBy(() -> patchTool.execute(objectMapper.readTree("""
                {"path":".env","expectedSha256":"%s","replacements":[{"oldText":"TOKEN","newText":"x"}]}
                """.formatted(sha)))).hasMessageContaining("受保护");
        var duplicateSha = RemoteSftpWorkspace.sha256(Files.readAllBytes(root.resolve("workspace/src/Duplicate.txt")));
        assertThatThrownBy(() -> patchTool.execute(objectMapper.readTree("""
                {"path":"src/Duplicate.txt","expectedSha256":"%s","replacements":[{"oldText":"same","newText":"x"}]}
                """.formatted(duplicateSha)))).hasMessageContaining("出现多次");
        var binarySha = RemoteSftpWorkspace.sha256(Files.readAllBytes(root.resolve("workspace/src/Binary.bin")));
        assertThatThrownBy(() -> patchTool.execute(objectMapper.readTree("""
                {"path":"src/Binary.bin","expectedSha256":"%s","replacements":[{"oldText":"a","newText":"x"}]}
                """.formatted(binarySha)))).hasMessageContaining("二进制");
        assertThatThrownBy(() -> patchTool.execute(objectMapper.readTree("""
                {"path":"src/Missing.txt","expectedSha256":"%s","replacements":[{"oldText":"a","newText":"x"}]}
                """.formatted(sha)))).isInstanceOf(Exception.class);
        assertThat(Files.readAllBytes(file)).isEqualTo(original);
        assertThat(Files.readString(root.resolve("workspace/src/Duplicate.txt"))).isEqualTo("same\nsame");
        assertThat(Files.readAllBytes(root.resolve("workspace/src/Binary.bin"))).containsExactly('a', 0, 'b');
    }

    @Test
    void rejectsSftpV3WithoutPosixRenameInsteadOfUsingUnsafeFallback() throws Exception {
        SftpModuleProperties.OPENSSH_EXTENSIONS.set(server, "fsync@openssh.com=1");
        var patchTool = tools(properties).get(3);
        var file = root.resolve("workspace/src/App.java");
        var original = Files.readAllBytes(file);
        var sha = RemoteSftpWorkspace.sha256(original);

        assertThatThrownBy(() -> patchTool.execute(objectMapper.readTree("""
                {"path":"src/App.java","expectedSha256":"%s","replacements":[{"oldText":"二","newText":"two"}]}
                """.formatted(sha))))
                .hasMessageContaining("未提供 posix-rename@openssh.com");
        assertThat(Files.readAllBytes(file)).isEqualTo(original);
        try (var siblings = Files.list(file.getParent())) {
            assertThat(siblings.map(Path::getFileName).map(Path::toString))
                    .noneMatch(name -> name.startsWith(".agent-studio-patch-"));
        }
    }

    @Test
    void rejectsTraversalProtectedFilesAndWrongHostFingerprint() throws Exception {
        var tools = tools(properties);
        assertThatThrownBy(() -> tools.get(2).execute(objectMapper.readTree("{\"path\":\"../outside\"}")))
                .hasMessageContaining("越出远程工作区");
        assertThatThrownBy(() -> tools.get(2).execute(objectMapper.readTree("{\"path\":\".env\"}")))
                .hasMessageContaining("受保护");
        var wrong = new SshWorkspaceProperties("127.0.0.1", server.getPort(), "tester", "/workspace",
                "SHA256:" + "A".repeat(43), "TEST_SSH_PASSWORD", Duration.ofSeconds(5));
        assertThatThrownBy(() -> tools(wrong).getFirst().execute(objectMapper.readTree("{}")))
                .isInstanceOf(SecurityException.class).hasMessageContaining("指纹不匹配");
    }

    @Test
    void inspectsHostFingerprintOnlyAfterSshKeyExchange() throws Exception {
        var secrets = mock(SecretResolver.class);
        var fingerprint = new SftpSessionFactory(secrets).inspectFingerprint(
                "127.0.0.1", server.getPort(), Duration.ofSeconds(5));

        assertThat(fingerprint.sha256()).isEqualTo(properties.hostKeySha256());
        assertThat(fingerprint.algorithm()).isNotBlank();
        assertThat(fingerprint.warning()).contains("独立核对");
    }

    private List<com.agentstudio.tool.AgentTool> tools(SshWorkspaceProperties configured) {
        var workspace = workspace(configured);
        return List.of(new ListRemoteDirectoryTool(workspace, objectMapper),
                new SearchRemoteFilesTool(workspace, objectMapper), new ReadRemoteTextFileTool(workspace, objectMapper),
                new ApplyRemoteTextPatchTool(workspace, objectMapper));
    }

    private RemoteSftpWorkspace workspace(SshWorkspaceProperties configured) {
        var secrets = mock(SecretResolver.class);
        when(secrets.resolve("TEST_SSH_PASSWORD")).thenReturn(Optional.of("password"));
        return new RemoteSftpWorkspace(new SftpSessionFactory(secrets), configured);
    }
}

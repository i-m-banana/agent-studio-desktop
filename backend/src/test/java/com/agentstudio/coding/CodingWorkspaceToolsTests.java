package com.agentstudio.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import com.agentstudio.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodingWorkspaceToolsTests {
    @TempDir Path temporaryDirectory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registersCodingToolsWithExplicitRiskBoundaries() throws Exception {
        var workspace = workspace();
        var commands = new WorkspaceVerificationCommands("mvn", "npm");
        var tools = List.of(
                new ListWorkspaceDirectoryTool(workspace, objectMapper),
                new SearchWorkspaceFilesTool(workspace, objectMapper),
                new ReadWorkspaceTextFileTool(workspace, objectMapper),
                new ApplyWorkspaceTextPatchTool(workspace, objectMapper),
                new RunWorkspaceVerificationTool(workspace, objectMapper, commands));
        var registry = new ToolRegistry(tools, objectMapper);

        assertThat(registry.descriptors()).extracting(descriptor -> descriptor.name())
                .containsExactlyInAnyOrder(
                        "list_workspace_directory", "search_workspace_files", "read_workspace_text_file",
                        "apply_workspace_text_patch", "run_workspace_verification");
        assertThat(registry.descriptors()).filteredOn(descriptor -> Set.of(
                        "apply_workspace_text_patch", "run_workspace_verification").contains(descriptor.name()))
                .allSatisfy(descriptor -> {
                    assertThat(descriptor.riskLevel()).isEqualTo("HIGH");
                });
        assertThat(registry.descriptors()).filteredOn(descriptor -> descriptor.name().equals("apply_workspace_text_patch"))
                .singleElement()
                .satisfies(descriptor -> {
                    assertThat(descriptor.capability()).isEqualTo("WRITE");
                });
        assertThat(registry.descriptors()).filteredOn(descriptor -> descriptor.name().equals("run_workspace_verification"))
                .singleElement().satisfies(descriptor -> assertThat(descriptor.capability()).isEqualTo("EXECUTE"));
        assertThat(registry.descriptors()).filteredOn(descriptor -> Set.of(
                        "list_workspace_directory", "search_workspace_files", "read_workspace_text_file")
                        .contains(descriptor.name()))
                .allSatisfy(descriptor -> {
            assertThat(descriptor.capability()).isEqualTo("READ");
            assertThat(descriptor.riskLevel()).isEqualTo("LOW");
        });
    }

    @Test
    void listsDirectoriesAndSearchesOnlyFilePaths() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("src/main"));
        Files.createDirectories(temporaryDirectory.resolve("node_modules/package"));
        Files.createDirectories(temporaryDirectory.resolve("target/classes"));
        Files.writeString(temporaryDirectory.resolve("src/main/AppService.java"), "class AppService {}", StandardCharsets.UTF_8);
        Files.writeString(temporaryDirectory.resolve("node_modules/package/AppService.js"), "generated", StandardCharsets.UTF_8);
        Files.writeString(temporaryDirectory.resolve("target/classes/AppService.class"), "generated", StandardCharsets.UTF_8);
        Files.writeString(temporaryDirectory.resolve("README.md"), "AppService is documented here", StandardCharsets.UTF_8);
        Files.writeString(temporaryDirectory.resolve(".env"), "TOKEN=must-not-leak", StandardCharsets.UTF_8);
        var workspace = workspace();
        var list = new ListWorkspaceDirectoryTool(workspace, objectMapper);
        var search = new SearchWorkspaceFilesTool(workspace, objectMapper);

        var listing = objectMapper.readTree(list.execute(objectMapper.readTree("{\"path\":\"src\"}")));
        assertThat(listing.path("entries").toString()).contains("main", "DIRECTORY");
        var result = objectMapper.readTree(search.execute(objectMapper.readTree("{\"query\":\"appservice\"}")));
        assertThat(result.path("files").toString()).contains("src/main/AppService.java");
        assertThat(result.path("files").toString()).doesNotContain(
                "README.md", ".env", "must-not-leak", "node_modules", "target/classes");
        assertThat(result.path("skippedDirectories").asInt()).isGreaterThanOrEqualTo(2);

        var explicitGeneratedSearch = objectMapper.readTree(search.execute(objectMapper.readTree(
                "{\"query\":\"appservice\",\"path\":\"node_modules\"}")));
        assertThat(explicitGeneratedSearch.path("files").toString())
                .contains("node_modules/package/AppService.js");

        var rootListing = objectMapper.readTree(list.execute(objectMapper.readTree("{}")));
        assertThat(rootListing.path("entries").toString()).doesNotContain(".env");
    }

    @Test
    void stopsScanningAfterCollectingOneExtraResult() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("src"));
        for (int index = 0; index < 20; index++) {
            Files.writeString(temporaryDirectory.resolve("src/match-" + index + ".txt"), "ok", StandardCharsets.UTF_8);
        }
        var tool = new SearchWorkspaceFilesTool(workspace(), objectMapper);

        var result = objectMapper.readTree(tool.execute(objectMapper.readTree(
                "{\"query\":\"match-\",\"maxResults\":1}")));

        assertThat(result.path("files").size()).isEqualTo(1);
        assertThat(result.path("truncated").asBoolean()).isTrue();
        assertThat(result.path("scannedEntries").asInt()).isLessThan(20);
    }

    @Test
    void readsBoundedUtf8LineWindow() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("src"));
        Files.writeString(temporaryDirectory.resolve("src/demo.txt"), "一\n二\n三\n四", StandardCharsets.UTF_8);
        var tool = new ReadWorkspaceTextFileTool(workspace(), objectMapper);

        var result = objectMapper.readTree(tool.execute(objectMapper.readTree(
                "{\"path\":\"src/demo.txt\",\"startLine\":2,\"maxLines\":2}")));

        assertThat(result.path("content").asText()).isEqualTo("二\n三");
        assertThat(result.path("startLine").asInt()).isEqualTo(2);
        assertThat(result.path("endLine").asInt()).isEqualTo(3);
        assertThat(result.path("truncated").asBoolean()).isTrue();
        assertThat(result.path("sha256").asText()).isEqualTo(sha256("一\n二\n三\n四".getBytes(StandardCharsets.UTF_8)));
        assertThat(result.path("sizeBytes").asLong()).isEqualTo(Files.size(temporaryDirectory.resolve("src/demo.txt")));
    }

    @Test
    void appliesExactPatchToExistingFileWithMatchingDigest() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("src"));
        var file = temporaryDirectory.resolve("src/demo.txt");
        var original = "alpha\nbeta\ngamma\n";
        Files.writeString(file, original, StandardCharsets.UTF_8);
        var tool = new ApplyWorkspaceTextPatchTool(workspace(), objectMapper);

        var arguments = objectMapper.createObjectNode()
                .put("path", "src/demo.txt")
                .put("expectedSha256", sha256(original.getBytes(StandardCharsets.UTF_8)));
        arguments.putArray("replacements").addObject().put("oldText", "beta").put("newText", "changed");
        var result = objectMapper.readTree(tool.execute(arguments));

        assertThat(Files.readString(file)).isEqualTo("alpha\nchanged\ngamma\n");
        assertThat(result.path("updated").asBoolean()).isTrue();
        assertThat(result.path("replacementsApplied").asInt()).isEqualTo(1);
        assertThat(result.path("beforeSha256").asText()).isEqualTo(sha256(original.getBytes(StandardCharsets.UTF_8)));
        assertThat(result.path("afterSha256").asText())
                .isEqualTo(sha256("alpha\nchanged\ngamma\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsStaleAmbiguousAndInvalidPatchesWithoutChangingFile() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("src"));
        var file = temporaryDirectory.resolve("src/demo.txt");
        var original = "same\nsame\n";
        Files.writeString(file, original, StandardCharsets.UTF_8);
        var tool = new ApplyWorkspaceTextPatchTool(workspace(), objectMapper);

        assertThatThrownBy(() -> tool.execute(objectMapper.readTree("""
                {"path":"src/demo.txt","expectedSha256":"%s","replacements":[{"oldText":"same","newText":"new"}]}
                """.formatted("0".repeat(64))))).hasMessageContaining("内容已变化");
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree("""
                {"path":"src/demo.txt","expectedSha256":"%s","replacements":[{"oldText":"same","newText":"new"}]}
                """.formatted(sha256(original.getBytes(StandardCharsets.UTF_8)))))).hasMessageContaining("出现多次");
        Files.writeString(file, "aaa", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree("""
                {"path":"src/demo.txt","expectedSha256":"%s","replacements":[{"oldText":"aa","newText":"b"}]}
                """.formatted(sha256("aaa".getBytes(StandardCharsets.UTF_8)))))).hasMessageContaining("出现多次");
        Files.writeString(file, original, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree("""
                {"path":"../demo.txt","expectedSha256":"%s","replacements":[{"oldText":"same","newText":"new"}]}
                """.formatted(sha256(original.getBytes(StandardCharsets.UTF_8)))))).hasMessageContaining("不能越出");
        Files.writeString(temporaryDirectory.resolve(".env"), "TOKEN=fake", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree("""
                {"path":".env","expectedSha256":"%s","replacements":[{"oldText":"fake","newText":"changed"}]}
                """.formatted(sha256("TOKEN=fake".getBytes(StandardCharsets.UTF_8)))))).hasMessageContaining("受保护");
        var binary = new byte[] { 1, 0, 2 };
        Files.write(temporaryDirectory.resolve("binary.dat"), binary);
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree("""
                {"path":"binary.dat","expectedSha256":"%s","replacements":[{"oldText":"x","newText":"y"}]}
                """.formatted(sha256(binary))))).hasMessageContaining("二进制");
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree("""
                {"path":"missing.txt","expectedSha256":"%s","replacements":[{"oldText":"x","newText":"y"}]}
                """.formatted(sha256("x".getBytes(StandardCharsets.UTF_8)))))).hasMessageContaining("路径不存在");
        assertThat(Files.readString(file)).isEqualTo(original);
    }

    @Test
    void runsOnlyFixedVerificationTaskAndReturnsBoundedResult() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.writeString(temporaryDirectory.resolve("project/package.json"), "{}", StandardCharsets.UTF_8);
        var commandDirectory = Files.createTempDirectory("agent-studio-verification-command-");
        var command = createTestCommand(commandDirectory, "success", "verification-ok", 0, false);
        try {
            var commands = new WorkspaceVerificationCommands(command.toString(), command.toString());
            var tool = new RunWorkspaceVerificationTool(workspace(), objectMapper, commands);

            var result = objectMapper.readTree(tool.execute(objectMapper.readTree(
                    "{\"path\":\"project\",\"task\":\"NPM_BUILD\"}")));

            assertThat(result.path("successful").asBoolean()).isTrue();
            assertThat(result.path("exitCode").asInt()).isZero();
            assertThat(result.path("output").asText()).contains("verification-ok");
            assertThat(result.path("outputTruncated").asBoolean()).isFalse();
        } finally {
            Files.deleteIfExists(command);
            Files.deleteIfExists(commandDirectory);
        }
    }

    @Test
    void rejectsMissingMarkerUnsupportedTaskAndWorkspaceLauncher() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("project"));
        var launcher = createTestCommand(temporaryDirectory, "launcher", "ok", 0, false);
        var commands = new WorkspaceVerificationCommands(launcher.toString(), launcher.toString());
        var tool = new RunWorkspaceVerificationTool(workspace(), objectMapper, commands);

        assertThatThrownBy(() -> tool.execute(objectMapper.readTree(
                "{\"path\":\"project\",\"task\":\"NPM_BUILD\"}"))).hasMessageContaining("package.json");
        Files.writeString(temporaryDirectory.resolve("project/package.json"), "{}", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree(
                "{\"path\":\"project\",\"task\":\"SHELL\"}"))).hasMessageContaining("只允许");
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree(
                "{\"path\":\"project\",\"task\":\"NPM_BUILD\"}"))).hasMessageContaining("代码工作区内");
    }

    @Test
    void terminatesVerificationThatExceedsItsBudget() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.writeString(temporaryDirectory.resolve("project/package.json"), "{}", StandardCharsets.UTF_8);
        var commandDirectory = Files.createTempDirectory("agent-studio-verification-timeout-");
        var command = createTestCommand(commandDirectory, "slow", "starting", 0, true);
        try {
            var commands = new WorkspaceVerificationCommands(command.toString(), command.toString());
            var tool = new RunWorkspaceVerificationTool(
                    workspace(), objectMapper, commands, java.time.Duration.ofMillis(150));

            assertThatThrownBy(() -> tool.execute(objectMapper.readTree(
                    "{\"path\":\"project\",\"task\":\"NPM_TEST\"}"))).hasMessageContaining("已终止进程树");
        } finally {
            Files.deleteIfExists(command);
            Files.deleteIfExists(commandDirectory);
        }
    }

    @Test
    void reportsNonZeroExitAndBoundsLargeProcessOutput() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.writeString(temporaryDirectory.resolve("project/package.json"), "{}", StandardCharsets.UTF_8);
        var commandDirectory = Files.createTempDirectory("agent-studio-verification-output-");
        var command = createNoisyTestCommand(commandDirectory);
        try {
            var commands = new WorkspaceVerificationCommands(command.toString(), command.toString());
            var tool = new RunWorkspaceVerificationTool(workspace(), objectMapper, commands);

            var result = objectMapper.readTree(tool.execute(objectMapper.readTree(
                    "{\"path\":\"project\",\"task\":\"NPM_TEST\"}")));

            assertThat(result.path("successful").asBoolean()).isFalse();
            assertThat(result.path("exitCode").asInt()).isEqualTo(7);
            assertThat(result.path("outputTruncated").asBoolean()).isTrue();
            assertThat(result.path("output").asText()).contains("输出已截断").hasSizeLessThan(16_100);
        } finally {
            Files.deleteIfExists(command);
            Files.deleteIfExists(commandDirectory);
        }
    }

    @Test
    void rejectsTraversalAbsoluteProtectedAndBinaryPaths() throws Exception {
        Files.writeString(temporaryDirectory.resolve(".env"), "SECRET=value", StandardCharsets.UTF_8);
        Files.write(temporaryDirectory.resolve("binary.dat"), new byte[] { 1, 0, 2 });
        var tool = new ReadWorkspaceTextFileTool(workspace(), objectMapper);

        assertThatThrownBy(() -> tool.execute(objectMapper.readTree("{\"path\":\"../outside.txt\"}")))
                .hasMessageContaining("不能越出");
        assertThatThrownBy(() -> tool.execute(objectMapper.createObjectNode().put(
                "path", temporaryDirectory.resolve("binary.dat").toAbsolutePath().toString())))
                .hasMessageContaining("相对路径");
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree("{\"path\":\"binary.dat:stream\"}")))
                .hasMessageContaining("数据流");
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree("{\"path\":\".env\"}")))
                .hasMessageContaining("受保护");
        assertThatThrownBy(() -> tool.execute(objectMapper.readTree("{\"path\":\"binary.dat\"}")))
                .hasMessageContaining("二进制");
    }

    @Test
    void rejectsSymlinkThatCouldEscapeWorkspaceWhenSupported() throws Exception {
        var outside = Files.createTempDirectory("coding-workspace-outside-");
        Files.writeString(outside.resolve("outside.txt"), "outside", StandardCharsets.UTF_8);
        var link = temporaryDirectory.resolve("escape");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException exception) {
            if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows")) {
                org.junit.jupiter.api.Assumptions.abort("当前文件系统不允许创建符号链接");
            }
            var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                    link.toString(), outside.toString()).redirectErrorStream(true).start();
            if (process.waitFor() != 0) {
                org.junit.jupiter.api.Assumptions.abort("当前 Windows 文件系统不允许创建目录联接");
            }
        }
        var tool = new ReadWorkspaceTextFileTool(workspace(), objectMapper);

        try {
            assertThatThrownBy(() -> tool.execute(objectMapper.readTree("{\"path\":\"escape/outside.txt\"}")))
                    .satisfies(error -> assertThat(error.getMessage())
                            .containsAnyOf("符号链接", "不能越出代码工作区"));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside.resolve("outside.txt"));
            Files.deleteIfExists(outside);
        }
    }

    private CodingWorkspace workspace() {
        return new CodingWorkspace(temporaryDirectory.toString());
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private Path createTestCommand(Path directory, String name, String output, int exitCode, boolean slow)
            throws Exception {
        var windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows");
        var command = directory.resolve(name + (windows ? ".cmd" : ".sh"));
        if (windows) {
            Files.writeString(command, "@echo off\r\necho " + output + "\r\n"
                    + (slow ? "ping -n 10 127.0.0.1 >nul\r\n" : "")
                    + "exit /b " + exitCode + "\r\n", StandardCharsets.UTF_8);
        } else {
            Files.writeString(command, "#!/bin/sh\necho " + output + "\n"
                    + (slow ? "sleep 10\n" : "") + "exit " + exitCode + "\n", StandardCharsets.UTF_8);
            command.toFile().setExecutable(true);
        }
        return command;
    }

    private Path createNoisyTestCommand(Path directory) throws Exception {
        var windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows");
        var command = directory.resolve("noisy" + (windows ? ".cmd" : ".sh"));
        if (windows) {
            Files.writeString(command, "@echo off\r\nfor /L %%i in (1,1,3000) do echo output-line-%%i\r\nexit /b 7\r\n",
                    StandardCharsets.UTF_8);
        } else {
            Files.writeString(command, "#!/bin/sh\ni=1\nwhile [ $i -le 3000 ]; do echo output-line-$i; i=$((i+1)); done\nexit 7\n",
                    StandardCharsets.UTF_8);
            command.toFile().setExecutable(true);
        }
        return command;
    }
}

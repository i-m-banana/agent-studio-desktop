package com.agentstudio.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.agentstudio.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodingWorkspaceToolsTests {
    @TempDir Path temporaryDirectory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registersAllReadOnlyCodingTools() throws Exception {
        var workspace = workspace();
        var tools = List.of(
                new ListWorkspaceDirectoryTool(workspace, objectMapper),
                new SearchWorkspaceFilesTool(workspace, objectMapper),
                new ReadWorkspaceTextFileTool(workspace, objectMapper));
        var registry = new ToolRegistry(tools, objectMapper);

        assertThat(registry.descriptors()).extracting(descriptor -> descriptor.name())
                .containsExactlyInAnyOrder(
                        "list_workspace_directory", "search_workspace_files", "read_workspace_text_file");
        assertThat(registry.descriptors()).allSatisfy(descriptor -> {
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
}

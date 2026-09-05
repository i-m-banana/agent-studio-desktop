package com.agentstudio.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriteWorkspaceNoteToolTests {
    @TempDir Path directory;

    @Test
    void createsNewFileInsideControlledWorkspaceAndRejectsTraversal() throws Exception {
        var tool = new WriteWorkspaceNoteTool(directory.toString());
        var mapper = new ObjectMapper();
        var output = tool.execute(mapper.readTree("{\"fileName\":\"approved.md\",\"content\":\"safe note\"}"));

        assertThat(output).contains("tool-workspace/approved.md");
        assertThat(Files.readString(directory.resolve("tool-workspace/approved.md"))).isEqualTo("safe note");
        assertThatThrownBy(() -> tool.execute(mapper.readTree(
                "{\"fileName\":\"../outside.md\",\"content\":\"blocked\"}")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package com.agentstudio.execution;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentstudio.tool.WriteWorkspaceNoteTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ToolInputValidatorTests {
    private final ToolInputValidator validator = new ToolInputValidator(new ObjectMapper());
    private final com.agentstudio.tool.ToolDescriptor descriptor =
            new WriteWorkspaceNoteTool("../data").descriptor();

    @Test
    void rejectsMissingRequiredProperty() {
        assertThatThrownBy(() -> validator.validate(descriptor, "{\"fileName\":\"a.md\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少必填参数：content");
    }

    @Test
    void rejectsUnexpectedProperty() {
        assertThatThrownBy(() -> validator.validate(descriptor,
                "{\"fileName\":\"a.md\",\"content\":\"ok\",\"command\":\"whoami\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的参数：command");
    }

    @Test
    void rejectsWrongPropertyType() {
        assertThatThrownBy(() -> validator.validate(descriptor,
                "{\"fileName\":\"a.md\",\"content\":123}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("参数 content 类型必须为 string");
    }
}

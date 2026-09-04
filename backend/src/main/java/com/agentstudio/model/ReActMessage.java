package com.agentstudio.model;

import java.util.List;

public record ReActMessage(
        String role,
        String content,
        String toolCallId,
        List<ModelToolCall> toolCalls) {

    public static ReActMessage text(String role, String content) {
        return new ReActMessage(role, content, null, List.of());
    }

    public static ReActMessage assistant(ModelTurn turn) {
        return new ReActMessage("assistant", turn.content(), null, turn.toolCalls());
    }

    public static ReActMessage observation(String toolCallId, String content) {
        return new ReActMessage("tool", content, toolCallId, List.of());
    }
}

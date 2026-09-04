package com.agentstudio.model;

import java.util.List;

public record ModelTurn(String content, List<ModelToolCall> toolCalls) {
}

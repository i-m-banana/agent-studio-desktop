package com.agentstudio.model;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.agentstudio.agent.AgentVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class OpenAiCompatibleModelGateway implements StreamingModelGateway {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleModelGateway(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void stream(AgentVersion version, List<ModelMessage> messages, Consumer<String> onDelta) throws Exception {
        var apiKey = System.getenv(version.apiKeyEnv());
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("环境变量 " + version.apiKeyEnv() + " 未设置");
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("model", version.modelName());
        body.put("temperature", version.temperature());
        body.put("stream", true);
        body.put("messages", messages.stream()
                .map(message -> Map.of("role", message.role(), "content", message.content()))
                .toList());

        var request = HttpRequest.newBuilder(chatCompletionsUri(version.baseUrl()))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        try (var lines = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                var errorBody = lines.limit(20).reduce("", (left, right) -> left + right);
                throw new IOException("模型服务返回 HTTP " + response.statusCode() + ": " + abbreviate(errorBody));
            }
            var iterator = lines.iterator();
            while (iterator.hasNext()) {
                var line = iterator.next();
                if (!line.startsWith("data:")) {
                    continue;
                }
                var data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                var delta = extractDelta(data);
                if (!delta.isEmpty()) {
                    onDelta.accept(delta);
                }
            }
        }
    }

    private URI chatCompletionsUri(String baseUrl) {
        var normalized = baseUrl.replaceAll("/+$", "");
        if (normalized.endsWith("/chat/completions")) {
            return URI.create(normalized);
        }
        return URI.create(normalized + "/chat/completions");
    }

    private String extractDelta(String data) throws IOException {
        JsonNode root = objectMapper.readTree(data);
        return root.path("choices").path(0).path("delta").path("content").asText("");
    }

    private String abbreviate(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500) + "…";
    }
}


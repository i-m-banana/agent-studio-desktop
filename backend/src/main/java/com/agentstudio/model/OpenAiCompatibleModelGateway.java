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
import com.agentstudio.secret.SecretResolver;
import com.agentstudio.tool.ToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class OpenAiCompatibleModelGateway implements StreamingModelGateway {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SecretResolver secrets;

    public OpenAiCompatibleModelGateway(HttpClient httpClient, ObjectMapper objectMapper, SecretResolver secrets) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.secrets = secrets;
    }

    @Override
    public void stream(AgentVersion version, List<ModelMessage> messages, Consumer<String> onDelta) throws Exception {
        var apiKey = apiKey(version);

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

    @Override
    public ModelTurn complete(AgentVersion version, List<ReActMessage> messages,
                              List<ToolDescriptor> tools) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", version.modelName());
        body.put("temperature", version.temperature());
        body.put("stream", false);
        body.put("messages", messages.stream().map(this::messageBody).toList());
        if (!tools.isEmpty()) {
            body.put("tools", tools.stream().map(tool -> Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", tool.name(),
                            "description", tool.description(),
                            "parameters", tool.inputSchema()))).toList());
            body.put("tool_choice", "auto");
        }

        var request = HttpRequest.newBuilder(chatCompletionsUri(version.baseUrl()))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey(version))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("模型服务返回 HTTP " + response.statusCode() + ": "
                    + abbreviate(response.body()));
        }
        var message = objectMapper.readTree(response.body()).path("choices").path(0).path("message");
        if (message.isMissingNode()) throw new IOException("模型服务未返回 message");
        var calls = new java.util.ArrayList<ModelToolCall>();
        for (var call : message.path("tool_calls")) {
            calls.add(new ModelToolCall(call.path("id").asText(),
                    call.path("function").path("name").asText(),
                    call.path("function").path("arguments").asText("{}")));
        }
        return new ModelTurn(message.path("content").asText(""), List.copyOf(calls));
    }

    private Map<String, Object> messageBody(ReActMessage message) {
        var body = new LinkedHashMap<String, Object>();
        body.put("role", message.role());
        body.put("content", message.content() == null ? "" : message.content());
        if (message.toolCallId() != null) body.put("tool_call_id", message.toolCallId());
        if (!message.toolCalls().isEmpty()) {
            body.put("tool_calls", message.toolCalls().stream().map(call -> Map.of(
                    "id", call.id(), "type", "function",
                    "function", Map.of("name", call.name(), "arguments", call.argumentsJson()))).toList());
        }
        return body;
    }

    private String apiKey(AgentVersion version) {
        return secrets.resolve(version.apiKeyEnv()).orElseThrow(() ->
                new IllegalStateException("凭据 " + version.apiKeyEnv() + " 未配置"));
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

package com.agentstudio.knowledge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "agent-studio.embedding", name = "provider", havingValue = "ollama")
public class OllamaEmbeddingGateway implements EmbeddingGateway {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final String model;
    private final int dimensions;
    private final String indexVersion;
    private final String queryInstruction;

    public OllamaEmbeddingGateway(
            @Qualifier("modelHttpClient") HttpClient httpClient,
            ObjectMapper objectMapper,
            @Value("${agent-studio.embedding.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${agent-studio.embedding.model:qwen3-embedding:0.6b}") String model,
            @Value("${agent-studio.embedding.dimensions:1024}") int dimensions,
            @Value("${agent-studio.embedding.index-version:qwen3-0.6b-v1}") String indexVersion,
            @Value("${agent-studio.embedding.query-instruction:}")
            String queryInstruction) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/api/embed");
        this.model = model;
        this.dimensions = dimensions;
        this.indexVersion = indexVersion;
        this.queryInstruction = queryInstruction.trim();
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public String indexVersion() {
        return indexVersion;
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) throws Exception {
        if (texts.isEmpty()) return List.of();
        return embed(texts);
    }

    @Override
    public float[] embedQuery(String query) throws Exception {
        var input = queryInstruction.isBlank() ? query : queryInstruction + "\n" + query;
        return embed(List.of(input)).getFirst();
    }

    private List<float[]> embed(List<String> inputs) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("input", inputs);
        body.put("dimensions", dimensions);
        body.put("truncate", true);
        var request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IOException("无法连接 Ollama embedding 服务 " + endpoint
                    + "，请确认 Ollama 已启动且模型 " + model + " 可用", exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Ollama embedding 返回 HTTP " + response.statusCode() + ": "
                    + abbreviate(response.body()));
        }
        var root = objectMapper.readTree(response.body()).path("embeddings");
        if (!root.isArray() || root.size() != inputs.size()) {
            throw new IOException("Ollama embedding 返回数量不匹配");
        }
        var result = new java.util.ArrayList<float[]>(root.size());
        for (var item : root) {
            var vector = new float[item.size()];
            for (int index = 0; index < item.size(); index++) vector[index] = item.get(index).floatValue();
            EmbeddingVectors.validate(vector, dimensions);
            result.add(vector);
        }
        return List.copyOf(result);
    }

    private String abbreviate(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500) + "…";
    }
}

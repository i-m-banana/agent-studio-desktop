package com.agentstudio.knowledge;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaEmbeddingGatewayTests {

    private HttpServer server;
    private final List<String> requestBodies = new ArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(body);
            var inputCount = new ObjectMapper().readTree(body).path("input").size();
            var vectors = new ArrayList<List<Double>>();
            for (int index = 0; index < inputCount; index++) vectors.add(List.of(1.0, 0.0, 0.0));
            var response = new ObjectMapper().writeValueAsBytes(java.util.Map.of("embeddings", vectors));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void batchesDocumentsAndAddsInstructionOnlyToQuery() throws Exception {
        var gateway = new OllamaEmbeddingGateway(HttpClient.newHttpClient(), new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-embedding", 3,
                "test-v1", "检索项目技术片段：");

        var documents = gateway.embedDocuments(List.of("文档一", "文档二"));
        var query = gateway.embedQuery("为什么使用不可变版本？");

        assertThat(documents).hasSize(2).allSatisfy(vector -> assertThat(vector).hasSize(3));
        assertThat(query).containsExactly(1.0f, 0.0f, 0.0f);
        assertThat(requestBodies.get(0)).contains("文档一", "文档二", "\"dimensions\":3");
        assertThat(requestBodies.get(0)).doesNotContain("检索项目技术片段");
        assertThat(requestBodies.get(1)).contains("检索项目技术片段", "为什么使用不可变版本");
    }
}

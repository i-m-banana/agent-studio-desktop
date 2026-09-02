package com.agentstudio.knowledge;

import java.util.List;

public interface EmbeddingGateway {

    int dimensions();

    String modelName();

    String indexVersion();

    List<float[]> embedDocuments(List<String> texts) throws Exception;

    float[] embedQuery(String query) throws Exception;
}

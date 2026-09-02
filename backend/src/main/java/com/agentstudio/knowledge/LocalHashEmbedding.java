package com.agentstudio.knowledge;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(prefix = "agent-studio.embedding", name = "provider", havingValue = "local-hash",
        matchIfMissing = true)
public class LocalHashEmbedding implements EmbeddingGateway {

    public static final int DIMENSIONS = 384;

    public float[] embed(String text) {
        var vector = new float[DIMENSIONS];
        for (var feature : features(text)) {
            var hash = feature.hashCode();
            var index = Math.floorMod(hash, DIMENSIONS);
            vector[index] += (hash & 1) == 0 ? 1.0f : -1.0f;
        }
        normalize(vector);
        return vector;
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public String modelName() {
        return "local-hash";
    }

    @Override
    public String indexVersion() {
        return "local-hash-v1";
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    @Override
    public float[] embedQuery(String query) {
        return embed(query);
    }

    private List<String> features(String source) {
        var text = Normalizer.normalize(source, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        var features = new ArrayList<String>();
        var words = text.split("[^\\p{L}\\p{N}]+");
        for (var word : words) {
            if (!word.isBlank()) features.add("w:" + word);
        }
        var compact = text.replaceAll("\\s+", "");
        for (int index = 0; index + 1 < compact.length(); index++) {
            features.add("b:" + compact.substring(index, index + 2));
        }
        return features;
    }

    private void normalize(float[] vector) {
        double squared = 0;
        for (var value : vector) squared += value * value;
        if (squared == 0) return;
        var norm = Math.sqrt(squared);
        for (int index = 0; index < vector.length; index++) vector[index] /= (float) norm;
    }
}

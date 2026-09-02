package com.agentstudio.knowledge;

final class EmbeddingVectors {

    private EmbeddingVectors() {
    }

    static String asPgVector(float[] vector) {
        var builder = new StringBuilder(vector.length * 10).append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) builder.append(',');
            builder.append(vector[index]);
        }
        return builder.append(']').toString();
    }

    static void validate(float[] vector, int dimensions) {
        if (vector.length != dimensions) {
            throw new IllegalStateException("Embedding 维度不匹配，期望 " + dimensions + "，实际 " + vector.length);
        }
        for (var value : vector) {
            if (!Float.isFinite(value)) throw new IllegalStateException("Embedding 包含非有限数值");
        }
    }
}

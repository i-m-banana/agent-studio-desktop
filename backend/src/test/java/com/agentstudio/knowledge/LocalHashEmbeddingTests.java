package com.agentstudio.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LocalHashEmbeddingTests {

    private final LocalHashEmbedding embedding = new LocalHashEmbedding();

    @Test
    void producesNormalizedDeterministicVectors() {
        var first = embedding.embed("脑电信号分类与特征提取");
        var second = embedding.embed("脑电信号分类与特征提取");

        assertThat(first).hasSize(LocalHashEmbedding.DIMENSIONS).containsExactly(second);
        double squared = 0;
        for (var value : first) squared += value * value;
        assertThat(Math.sqrt(squared)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.0001));
    }
}

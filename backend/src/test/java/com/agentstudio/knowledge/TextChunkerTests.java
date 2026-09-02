package com.agentstudio.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextChunkerTests {

    private final TextChunker chunker = new TextChunker();

    @Test
    void splitsLongTextWithBoundedChunksAndOverlap() {
        var source = "第一章。" + "科研知识库需要保留来源和上下文。".repeat(180);
        var chunks = chunker.chunk(source);

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks).allMatch(chunk -> chunk.length() <= 1_100);
        assertThat(chunks.get(0).substring(chunks.get(0).length() - 40))
                .isSubstringOf(chunks.get(1));
    }
}


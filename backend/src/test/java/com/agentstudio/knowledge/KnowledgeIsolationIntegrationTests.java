package com.agentstudio.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.agentstudio.system.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class KnowledgeIsolationIntegrationTests {
    @Autowired KnowledgeService knowledge;
    @MockitoBean VectorChunkRepository vectors;

    @Test
    @SuppressWarnings("unchecked")
    void keepsChunksAndDeletionScopedToOwningKnowledgeBase() throws Exception {
        doNothing().when(vectors).replaceDocument(org.mockito.ArgumentMatchers.anyString(), anyList());
        var firstBase = knowledge.createBase(new KnowledgeBaseRequest("isolation-a-" + UUID.randomUUID(), "A"));
        var secondBase = knowledge.createBase(new KnowledgeBaseRequest("isolation-b-" + UUID.randomUUID(), "B"));
        var first = knowledge.importContent(firstBase.id(), "first.md", "text/markdown",
                "# Alpha\nOnly alpha facts belong here.".getBytes(StandardCharsets.UTF_8));
        var second = knowledge.importContent(secondBase.id(), "second.md", "text/markdown",
                "# Beta\nOnly beta facts belong here.".getBytes(StandardCharsets.UTF_8));

        var chunks = ArgumentCaptor.forClass(java.util.List.class);
        verify(vectors).replaceDocument(eq(first.id()), chunks.capture());
        assertThat((java.util.List<KnowledgeChunk>) chunks.getValue()).allMatch(chunk -> chunk.knowledgeBaseId().equals(firstBase.id()));
        assertThat(knowledge.documents(firstBase.id())).extracting(KnowledgeDocument::id).containsExactly(first.id());
        assertThat(knowledge.documents(secondBase.id())).extracting(KnowledgeDocument::id).containsExactly(second.id());

        assertThatThrownBy(() -> knowledge.deleteDocument(secondBase.id(), first.id()))
                .isInstanceOf(ApiException.class).hasMessageContaining("文档不存在");
        assertThat(knowledge.documents(firstBase.id())).hasSize(1);
        knowledge.deleteDocument(firstBase.id(), first.id());
        verify(vectors).deleteDocument(first.id());
        assertThat(knowledge.documents(firstBase.id())).isEmpty();
        assertThat(knowledge.documents(secondBase.id())).hasSize(1);
    }
}

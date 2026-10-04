package com.agentstudio.knowledge;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
class DocumentReadingTests {
    @Autowired KnowledgeService service;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @MockitoBean VectorChunkRepository vectors;

    @Test void readsLongTextWithoutGapsOrReindexingAndKeepsOwningBaseBoundary() throws Exception {
        var base=service.createBase(new KnowledgeBaseRequest("read-"+UUID.randomUUID(),"fixture"));
        var other=service.createBase(new KnowledgeBaseRequest("other-"+UUID.randomUUID(),"fixture"));
        var text="中文😀".repeat(5000);
        var document=service.importContent(base.id(),"large.txt","text/plain",text.getBytes(StandardCharsets.UTF_8));
        clearInvocations(vectors);
        var all=new StringBuilder(); int offset=0;
        while(true) {
            var page=service.preview(base.id(),document.id(),offset,7999);
            all.append(page.content());
            assertThat(page.offset()).isEqualTo(offset);
            assertThat(page.content()).doesNotContain("\uFFFD");
            if(page.nextOffset()==page.totalChars()) break;
            assertThat(page.nextOffset()).isGreaterThan(offset); offset=page.nextOffset();
        }
        assertThat(all.toString()).isEqualTo(text);
        verifyNoInteractions(vectors);
        mvc.perform(get("/api/knowledge-bases/{id}/documents/{doc}/text",other.id(),document.id())).andExpect(status().isNotFound());
        mvc.perform(get("/api/knowledge-bases/{id}/documents/{doc}/text",base.id(),document.id()).param("offset","-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/knowledge-bases/{id}/documents/{doc}/text",base.id(),document.id()).param("limit","16001")).andExpect(status().isBadRequest());
        assertThat(service.documents(base.id()).getFirst().sha256()).isEqualTo(document.sha256());
    }
}

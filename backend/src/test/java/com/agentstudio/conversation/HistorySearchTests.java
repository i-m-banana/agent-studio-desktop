package com.agentstudio.conversation;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.agentstudio.agent.*;
import com.agentstudio.model.*;
import com.agentstudio.runtime.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
class HistorySearchTests {
    @Autowired ConversationRepository conversations;
    @Autowired RunRepository runs;
    @Autowired AgentService agents;
    @Autowired ModelProfileService models;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("primaryNamedParameterJdbcTemplate") NamedParameterJdbcTemplate jdbc;

    String version() {
        var suffix=UUID.randomUUID().toString();
        var model=models.create(new ModelProfileRequest("search-"+suffix,"OPENAI_COMPATIBLE","https://example.com/v1","test","TEST_KEY",new BigDecimal("0.2")));
        return agents.publish(agents.create(new AgentDefinitionRequest("search-"+suffix,"fixture",model.id(),null,"test",List.of())).id()).id();
    }
    @Test void searchesEntireBodyAndSeparatesTrashWithoutTreatingWildcardsAsPatterns() throws Exception {
        var id=conversations.create(version()); var key="only-"+UUID.randomUUID();
        conversations.addMessage(id,"user","initial preview");
        conversations.addMessage(id,"assistant","later answer "+key+" literal 100% and a_b");
        assertThat(conversations.list(50,0,false,key)).extracting(ConversationRepository.Summary::id).containsExactly(id);
        assertThat(conversations.list(50,0,false,"100% and a_b")).extracting(ConversationRepository.Summary::id).containsExactly(id);
        assertThat(conversations.list(50,0,false,"' OR 1=1 --")).isEmpty();
        conversations.moveToTrash(List.of(id),true);
        assertThat(conversations.list(50,0,false,key)).isEmpty();
        assertThat(conversations.list(50,0,true,key)).extracting(ConversationRepository.Summary::id).containsExactly(id);
        mvc.perform(get("/api/conversations").param("query","x".repeat(201))).andExpect(status().isBadRequest());
        assertThat(conversations.historyMessages(id)).hasSize(2);
    }
    @Test void identifiesEachRunsOwnRequestInTheSameConversation() {
        var version=version(); var conversation=conversations.create(version);
        var firstKey="first-"+UUID.randomUUID(); var secondKey="second-"+UUID.randomUUID();
        conversations.addMessage(conversation,"user",firstKey);
        jdbc.update("UPDATE message SET created_at=:time WHERE conversation_id=:id",Map.of("time",Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),"id",conversation));
        var first=runs.start(conversation,version);
        jdbc.update("UPDATE agent_run SET started_at=:time WHERE id=:id",Map.of("time",Timestamp.from(Instant.parse("2020-01-01T00:00:01Z")),"id",first.id()));
        conversations.addMessage(conversation,"user",secondKey);
        jdbc.update("UPDATE message SET created_at=:time WHERE conversation_id=:id AND content=:content",Map.of("time",Timestamp.from(Instant.parse("2020-01-01T00:00:02Z")),"id",conversation,"content",secondKey));
        var second=runs.start(conversation,version);
        jdbc.update("UPDATE agent_run SET started_at=:time WHERE id=:id",Map.of("time",Timestamp.from(Instant.parse("2020-01-01T00:00:03Z")),"id",second.id()));
        assertThat(runs.list(50,0,firstKey,"")).extracting(RunSummary::id).containsExactly(first.id());
        assertThat(runs.list(50,0,secondKey,"")).extracting(RunSummary::id).containsExactly(second.id());
        assertThat(runs.list(50,0,secondKey,"").getFirst().preview()).isEqualTo(secondKey);
        runs.finish(first.id(),"COMPLETED",null); runs.finish(second.id(),"COMPLETED",null);
    }
    @Test void pagesOlderRunsWithDeterministicOrderingAndFiltersByActualState() throws Exception {
        var version=version(); var key="page-"+UUID.randomUUID();
        var first=runs.start(conversations.create(version),version);
        var second=runs.start(conversations.create(version),version);
        for(var run:List.of(first,second)) {
            conversations.addMessage(run.conversationId(),"user",key);
            jdbc.update("UPDATE message SET created_at=:time WHERE conversation_id=:id",Map.of("time",Timestamp.from(Instant.parse("2019-12-31T23:59:59Z")),"id",run.conversationId()));
            jdbc.update("UPDATE agent_run SET started_at=:time WHERE id=:id",Map.of("time",Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),"id",run.id()));
        }
        runs.finish(first.id(),"COMPLETED",null); runs.updateStatus(second.id(),"THINKING");
        var page1=runs.list(1,0,key,""); var page2=runs.list(1,1,key,"");
        assertThat(page1).hasSize(1); assertThat(page2).hasSize(1);
        assertThat(page1.getFirst().id()).isNotEqualTo(page2.getFirst().id());
        assertThat(runs.list(1,2,key,"")).isEmpty();
        assertThat(runs.list(50,0,key,"COMPLETED")).extracting(RunSummary::id).containsExactly(first.id());
        assertThat(runs.list(50,0,key,"RUNNING")).extracting(RunSummary::id).containsExactly(second.id());
        assertThat(page1.getFirst().preview()).isEqualTo(key); assertThat(page1.getFirst().agentName()).startsWith("search-");
        mvc.perform(get("/api/runs").param("status","bad")).andExpect(status().isBadRequest());
        var body=mvc.perform(get("/api/runs").param("query",key).param("offset","1").param("limit","1")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).size()).isEqualTo(1);
        runs.finish(second.id(),"CANCELLED","fixture complete");
    }
}

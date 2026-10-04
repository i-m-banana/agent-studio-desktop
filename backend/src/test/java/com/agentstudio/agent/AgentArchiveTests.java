package com.agentstudio.agent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.agentstudio.model.*;
import com.agentstudio.conversation.ConversationRepository;
import com.agentstudio.runtime.*;
import com.agentstudio.approval.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class AgentArchiveTests {
    @Autowired AgentService agents;
    @Autowired ModelProfileService models;
    @Autowired ConversationRepository conversations;
    @Autowired RunRepository runs;
    @Autowired ApprovalRepository approvals;
    @Autowired MockMvc mvc;
    AgentDefinition fixture() {
        var key=UUID.randomUUID().toString();
        var model=models.create(new ModelProfileRequest("archive-"+key,"OPENAI_COMPATIBLE","https://example.com/v1","test","TEST_KEY",new BigDecimal("0.2")));
        return agents.create(new AgentDefinitionRequest("archive-"+key,"fixture",model.id(),null,"prompt",List.of("current_time")));
    }
    @Test void wholeAgentArchiveIsReversibleWithoutChangingVersionsOrHistory() throws Exception {
        var agent=fixture(); var v1=agents.publish(agent.id()); var v2=agents.publish(agent.id());
        agents.archiveVersion(agent.id(),v1.id());
        var conversation=conversations.create(v2.id()); conversations.addMessage(conversation,"user","preserved");
        mvc.perform(post("/api/agents/"+agent.id()+"/archive")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
        var timestamp=agents.get(agent.id()).archivedAt(); agents.archive(agent.id(),true);
        assertThat(agents.get(agent.id()).archivedAt()).isEqualTo(timestamp);
        assertThat(agents.list()).extracting(AgentDefinition::id).doesNotContain(agent.id());
        assertThat(agents.list(true)).extracting(AgentDefinition::id).contains(agent.id());
        assertThat(agents.getVersion(v2.id()).systemPrompt()).isEqualTo(v2.systemPrompt());
        assertThat(agents.getVersion(v2.id()).archived()).isFalse();
        mvc.perform(get("/api/conversations/"+conversation)).andExpect(status().isOk()).andExpect(jsonPath("$.messages[0].content").value("preserved"));
        assertThatThrownBy(()->agents.publish(agent.id())).hasMessageContaining("已归档");
        assertThatThrownBy(()->agents.update(agent.id(),new AgentDefinitionRequest(agent.name(),"changed",agent.draftModelProfileId(),null,"changed",List.of()))).hasMessageContaining("已归档");
        mvc.perform(post("/api/chat/stream").contentType("application/json").content("{\"agentVersionId\":\""+v2.id()+"\",\"message\":\"new\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/agents/"+agent.id()+"/restore")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
        assertThat(agents.getVersion(v1.id()).archived()).isTrue();
        assertThat(conversations.historyMessages(conversation)).hasSize(1);
        assertThat(agents.publish(agent.id()).versionNumber()).isEqualTo(3);
    }
    @Test void activityOnlyOffersCurrentUnexpiredPendingApproval() throws Exception {
        var version=agents.publish(fixture().id()); var conversation=conversations.create(version.id());
        var run=runs.start(conversation,version.id());
        runs.addStep(run.id(),"TOOL_CALL","REQUESTED","call","current_time","{}",null,null);
        runs.updateStatus(run.id(),"WAITING_APPROVAL");
        var pending=new ApprovalRequest(UUID.randomUUID().toString(),run.id(),conversation,version.id(),"call","current_time","READ","HIGH","fixture","{}","a".repeat(64),"PENDING",null,Instant.now(),Instant.now().plusSeconds(90),null);
        approvals.insert(pending);
        mvc.perform(get("/api/runs/"+run.id()+"/activity")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WAITING_APPROVAL")).andExpect(jsonPath("$.pendingApproval.id").value(pending.id())).andExpect(jsonPath("$.lastStep.toolName").value("current_time"));
        approvals.decide(pending.id(),"REJECTED","fixture",Instant.now());
        mvc.perform(get("/api/runs/"+run.id()+"/activity")).andExpect(status().isOk()).andExpect(jsonPath("$.pendingApproval").isEmpty());
        runs.finish(run.id(),"CANCELLED","fixture complete");
        mvc.perform(get("/api/runs/"+run.id()+"/activity")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.pendingApproval").isEmpty());
        mvc.perform(get("/api/runs/not-present/activity")).andExpect(status().isNotFound());
    }
}

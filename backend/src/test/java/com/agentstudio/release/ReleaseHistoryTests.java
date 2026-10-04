package com.agentstudio.release;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import com.agentstudio.agent.*;
import com.agentstudio.model.*;
import com.agentstudio.conversation.ConversationRepository;
import com.agentstudio.execution.AuditRepository;
import com.agentstudio.runtime.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
class ReleaseHistoryTests {
    @Autowired ReleaseTaskRepository repository;
    @Autowired ReleaseTaskService service;
    @Autowired RunRepository runs;
    @Autowired AuditRepository audits;
    @Autowired ConversationRepository conversations;
    @Autowired AgentService agents;
    @Autowired ModelProfileService models;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired RunRecoveryService recovery;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("primaryNamedParameterJdbcTemplate")
    org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    AgentRun start() {
        var suffix = UUID.randomUUID().toString();
        var model = models.create(new ModelProfileRequest("history-"+suffix,"OPENAI_COMPATIBLE","https://example.com/v1",
                "test","TEST_KEY",new BigDecimal("0.2")));
        var version = agents.publish(agents.create(new AgentDefinitionRequest("history-"+suffix,"history",model.id(),null,
                "test",List.of("publish_remote_release"))).id());
        return runs.start(conversations.create(version.id()),version.id());
    }
    void audit(AgentRun run, String tool, String event, String state, String arguments) throws Exception {
        var hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(arguments.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        audits.add(run.id(),run.conversationId(),run.agentVersionId(),event,tool,"EXECUTE","HIGH",state,hash,"fixture");
    }
    void completed(AgentRun run, String tool, String receipt, boolean completionAudit) throws Exception {
        runs.addStep(run.id(),"TOOL_CALL","RUNNING","call",tool,"{}",null,null);
        audit(run,tool,"APPROVAL_DECIDED","APPROVED","{}");
        audit(run,tool,"TOOL_EXECUTION_STARTED","RUNNING","{}");
        if (completionAudit) audit(run,tool,"TOOL_EXECUTION_COMPLETED","COMPLETED","{}");
        runs.addStep(run.id(),"TOOL_RESULT","COMPLETED","call",tool,null,receipt,1L);
        runs.finish(run.id(),"COMPLETED",null);
    }
    ReleaseTaskService.Task task(AgentRun run) { return service.list(run.conversationId(),100).getFirst(); }

    @Test void persistsIdentityAndBackfillsIdempotentlyWithoutChangingOriginalEvidence() throws Exception {
        var run = start();
        completed(run,"prepare_release_candidate","{\"successful\":true,\"exitCode\":0,\"stage\":\"REMOTE_VERIFY\",\"releaseId\":\"candidate\",\"manifestSha256\":\"hash\"}",true);
        var before = task(run);
        repository.backfill(); repository.backfill();
        assertThat(service.list(run.conversationId(),100)).hasSize(1);
        assertThat(task(run).id()).isEqualTo(before.id());
        assertThat(task(run).status()).isEqualTo("SUCCEEDED");
        assertThat(task(run).observedAt()).isEqualTo(before.observedAt());
        assertThat(runs.find(run.id()).orElseThrow().steps()).hasSize(2);
    }
    @Test void conversationCompletedOrModelClaimsCannotEstablishDeployment() throws Exception {
        var run=start(); conversations.addMessage(run.conversationId(),"assistant","deployed=true, everything succeeded");
        runs.addStep(run.id(),"MODEL_CALL","COMPLETED",null,null,null,"DEPLOYED",1L);
        runs.finish(run.id(),"COMPLETED",null);
        assertThat(service.list(run.conversationId(),100)).isEmpty();
        var missing=start(); completed(missing,"publish_remote_release","{\"successful\":true,\"exitCode\":0,\"deployed\":true,\"rolledBack\":false,\"manualInterventionRequired\":false}",false);
        assertThat(task(missing).status()).isEqualTo("UNKNOWN");
    }
    @Test void distinguishesDeployedRestoredAndManualIntervention() throws Exception {
        var deployed=start(); completed(deployed,"publish_remote_release","{\"successful\":true,\"exitCode\":0,\"deployed\":true,\"rolledBack\":false,\"manualInterventionRequired\":false}",true);
        assertThat(task(deployed).status()).isEqualTo("DEPLOYED");
        var restored=start(); completed(restored,"publish_remote_release","{\"successful\":false,\"exitCode\":1,\"deployed\":false,\"rolledBack\":true,\"manualInterventionRequired\":false}",true);
        assertThat(task(restored).status()).isEqualTo("RESTORED");
        var manual=start(); completed(manual,"publish_remote_release","{\"successful\":false,\"exitCode\":1,\"rolledBack\":true,\"manualInterventionRequired\":true}",true);
        assertThat(task(manual).status()).isEqualTo("MANUAL_INTERVENTION");
    }
    @Test void interruptedAndRejectedTasksNeverReplayOrAcquireSuccess() throws Exception {
        var interrupted=start(); runs.addStep(interrupted.id(),"TOOL_CALL","RUNNING","call","publish_remote_release","{}",null,null);
        audit(interrupted,"publish_remote_release","TOOL_EXECUTION_STARTED","RUNNING","{}");
        runs.finish(interrupted.id(),"INTERRUPTED","restart");
        assertThat(task(interrupted).status()).isEqualTo("UNKNOWN");
        var rejected=start(); runs.addStep(rejected.id(),"TOOL_CALL","RUNNING","call","publish_remote_release","{}",null,null);
        audit(rejected,"publish_remote_release","TOOL_EXECUTION_SKIPPED","REJECTED","{}");
        runs.finish(rejected.id(),"COMPLETED",null);
        assertThat(task(rejected).status()).isEqualTo("NOT_EXECUTED");
        repository.backfill(); assertThat(runs.find(rejected.id()).orElseThrow().steps()).hasSize(1);
    }
    @Test void unrelatedArgumentAuditCannotConfirmTruncatedOrMissingReceipt() throws Exception {
        var run=start(); runs.addStep(run.id(),"TOOL_CALL","RUNNING","call","publish_remote_release","{}",null,null);
        audit(run,"publish_remote_release","APPROVAL_DECIDED","APPROVED","{\"different\":true}");
        audit(run,"publish_remote_release","TOOL_EXECUTION_COMPLETED","COMPLETED","{\"different\":true}");
        runs.addStep(run.id(),"TOOL_RESULT","COMPLETED","call","publish_remote_release",null,"{\"successful\":true",1L);
        runs.finish(run.id(),"COMPLETED",null);
        assertThat(task(run).status()).isEqualTo("UNKNOWN");
        assertThat(task(run).receipt()).isNull();
    }
    @Test void readsFullConversationWithOriginalVersionMessagesAndActiveRun() throws Exception {
        var run=start(); conversations.addMessage(run.conversationId(),"user","first user");
        conversations.addMessage(run.conversationId(),"assistant","first assistant");
        var response=mvc.perform(get("/api/conversations/{id}",run.conversationId())).andExpect(status().isOk()).andReturn();
        var detail=json.readTree(response.getResponse().getContentAsString());
        assertThat(detail.path("agentVersionId").asText()).isEqualTo(run.agentVersionId());
        assertThat(detail.path("messages").size()).isEqualTo(2);
        assertThat(detail.path("runs").get(0).path("status").asText()).isEqualTo("CREATED");
        assertThat(conversations.list(100,0).stream().filter(c->c.id().equals(run.conversationId())).findFirst().orElseThrow().activeRunId()).isEqualTo(run.id());
        mvc.perform(get("/api/conversations/missing")).andExpect(status().isNotFound());
        runs.finish(run.id(),"CANCELLED","test complete");
    }
    @Test void startupBackfillsLegacyCallsAndClosesActiveRunsWithoutReplaying() throws Exception {
        var run=start(); var stepId=UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO run_step (id,run_id,step_number,step_type,status,tool_call_id,tool_name,input_json,created_at)
                VALUES (:id,:runId,1,'TOOL_CALL','RUNNING','legacy','publish_remote_release','{}',:time)
                """,java.util.Map.of("id",stepId,"runId",run.id(),"time",java.sql.Timestamp.from(java.time.Instant.now())));
        audit(run,"publish_remote_release","TOOL_EXECUTION_STARTED","RUNNING","{}");
        assertThat(service.list(run.conversationId(),100)).isEmpty();
        recovery.closeInterruptedRuns(); var recovered=task(run);
        assertThat(recovered.status()).isEqualTo("UNKNOWN");
        assertThat(runs.find(run.id()).orElseThrow().status()).isEqualTo("INTERRUPTED");
        recovery.closeInterruptedRuns();
        assertThat(task(run).id()).isEqualTo(recovered.id());
        assertThat(runs.find(run.id()).orElseThrow().steps()).hasSize(2)
                .filteredOn(step -> step.stepType().equals("RUN_TERMINATION")).hasSize(1);
        assertThat(conversations.historyMessages(run.conversationId())).filteredOn(message -> message.role().equals("assistant"))
                .hasSize(1).allSatisfy(message -> assertThat(message.content()).contains("后台进程中断","不会自动续跑"));
    }
    @Test void recoveredActiveConversationRejectsDuplicateSendBeforeRecordingAnotherMessageOrRun() throws Exception {
        var run=start();
        var body=json.writeValueAsString(java.util.Map.of("agentVersionId",run.agentVersionId(),"conversationId",run.conversationId(),"message","do not duplicate"));
        mvc.perform(post("/api/chat/stream").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        assertThat(conversations.historyMessages(run.conversationId())).isEmpty();
        assertThat(runs.forConversation(run.conversationId())).hasSize(1);
        runs.finish(run.id(),"CANCELLED","test complete");
    }
    @Test void trashAndRestorePreserveMessagesAndReleaseEvidence() throws Exception {
        var run=start(); conversations.addMessage(run.conversationId(),"user","keep original message");
        completed(run,"publish_remote_release","{\"successful\":true,\"exitCode\":0,\"stage\":\"DEPLOYED\",\"deployed\":true}",true);
        var originalTask=task(run);
        var body=json.writeValueAsString(java.util.Map.of("ids",List.of(run.conversationId())));
        mvc.perform(post("/api/conversations/trash").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertThat(conversations.list(100,0)).noneMatch(c -> c.id().equals(run.conversationId()));
        assertThat(conversations.list(100,0,true)).anyMatch(c -> c.id().equals(run.conversationId()));
        mvc.perform(get("/api/conversations/"+run.conversationId())).andExpect(status().isNotFound());
        assertThat(task(run).id()).isEqualTo(originalTask.id());
        assertThat(runs.find(run.id()).orElseThrow().steps()).hasSize(2);
        mvc.perform(post("/api/conversations/restore").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertThat(conversations.findAgentVersionId(run.conversationId())).contains(run.agentVersionId());
        assertThat(conversations.historyMessages(run.conversationId()).getFirst().content()).isEqualTo("keep original message");
        mvc.perform(get("/api/conversations/"+run.conversationId())).andExpect(status().isOk());
    }
    @Test void batchTrashIsAtomicAndRejectsActiveOrMissingConversations() throws Exception {
        var finished=start(); runs.finish(finished.id(),"COMPLETED",null);
        var active=start();
        var body=json.writeValueAsString(java.util.Map.of("ids",List.of(finished.conversationId(),active.conversationId())));
        mvc.perform(post("/api/conversations/trash").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        assertThat(conversations.findAgentVersionId(finished.conversationId())).isPresent();
        body=json.writeValueAsString(java.util.Map.of("ids",List.of(finished.conversationId(),UUID.randomUUID().toString())));
        mvc.perform(post("/api/conversations/trash").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        assertThat(conversations.findAgentVersionId(finished.conversationId())).isPresent();
        mvc.perform(post("/api/conversations/trash").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"ids\":[]}")).andExpect(status().isBadRequest());
        runs.finish(active.id(),"CANCELLED","fixture complete");
    }
    @Test void olderImageReceiptIsNotRelabelledAsFailureOrCurrentRuntimeEvidence() throws Exception {
        var run=start(); completed(run,"build_release_candidate_image","{\"successful\":true,\"exitCode\":0,\"stage\":\"IMAGE_READY\"}",true);
        assertThat(task(run).status()).isEqualTo("INCOMPLETE_EVIDENCE");
        assertThat(task(run).receipt().path("successful").asBoolean()).isTrue();
    }
}

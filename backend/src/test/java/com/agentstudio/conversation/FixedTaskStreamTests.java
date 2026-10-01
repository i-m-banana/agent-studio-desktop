package com.agentstudio.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.agentstudio.agent.*;
import com.agentstudio.adapter.ssh.InspectRemoteDeploymentTool;
import com.agentstudio.adapter.ssh.PublishRemoteReleaseTool;
import com.agentstudio.knowledge.KnowledgeRetriever;
import com.agentstudio.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.http.MediaType;

/** Real registry/gateway/approval/audit/SSE, simulated remote adapter only. */
@SpringBootTest @AutoConfigureMockMvc
class FixedTaskStreamTests {
    @Autowired MockMvc mvc;
    @Autowired AgentService agents;
    @Autowired ModelProfileService models;
    @Autowired @Qualifier("primaryNamedParameterJdbcTemplate") NamedParameterJdbcTemplate jdbc;
    @MockitoBean StreamingModelGateway model;
    @MockitoBean KnowledgeRetriever knowledge;
    @MockitoSpyBean InspectRemoteDeploymentTool tool;
    @MockitoSpyBean PublishRemoteReleaseTool publishTool;

    String version(List<String> names) throws Exception {
        when(knowledge.retrieve(any(), any())).thenReturn(List.of());
        doReturn("SSH:fixed-test-target").when(tool).targetEnvironment();
        var suffix = UUID.randomUUID().toString();
        var profile = models.create(new ModelProfileRequest("fixed-" + suffix, "OPENAI_COMPATIBLE",
                "https://example.com/v1", "test", "TEST_MODEL_KEY", new BigDecimal("0.2")));
        return agents.publish(agents.create(new AgentDefinitionRequest("fixed-" + suffix, "test",
                profile.id(), null, "test", names)).id()).id();
    }

    String body(String version, String arguments) {
        return """
                {"agentVersionId":"%s","message":"请使用 inspect_remote_deployment 运行 DATABASE_SCHEMA",
                 "requestedTool":{"name":"inspect_remote_deployment","arguments":%s}}
                """.formatted(version, arguments);
    }

    String runWithApproval(boolean approve, String output) throws Exception {
        var version = version(List.of("inspect_remote_deployment"));
        doReturn(output).when(tool).execute(any());
        var result = mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                .content(body(version, "{\"task\":\"DATABASE_SCHEMA\"}")))
                .andExpect(request().asyncStarted()).andReturn();
        String approvalId = null;
        for (int i=0; i<150 && approvalId==null; i++) {
            var ids = jdbc.query("SELECT id FROM approval_request WHERE agent_version_id=:version AND status='PENDING'",
                    Map.of("version", version), (rs,n) -> rs.getString("id"));
            if (!ids.isEmpty()) approvalId=ids.getFirst(); else Thread.sleep(20);
        }
        assertThat(approvalId).isNotNull();
        mvc.perform(post("/api/approvals/{id}/" + (approve ? "approve" : "reject"), approvalId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test\"}"))
                .andExpect(status().isOk());
        var response = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(response).contains("USER_TOOL_REQUEST", "APPROVAL_REQUEST", "APPROVAL_RESULT");
        verify(model, never()).complete(any(), any(), any());
        return response;
    }

    private String publishBody(String version, boolean extra) {
        return "{\"agentVersionId\":\"" + version + "\",\"message\":\"请只使用 publish_remote_release 工具\",\"requestedTool\":{\"name\":\"publish_remote_release\",\"arguments\":{"
                + "\"releaseId\":\"20261001T120000Z-1234abcd\",\"manifestSha256\":\""+"a".repeat(64)+"\",\"imageId\":\"sha256:"+"b".repeat(64)+"\","
                + "\"schemaSha256\":\""+"c".repeat(64)+"\",\"backupId\":\"20261001T120000Z-5678abcd\",\"backupManifestSha256\":\""+"d".repeat(64)+"\","
                + "\"previousImageId\":\"sha256:"+"e".repeat(64)+"\",\"productionSha256\":\""+"f".repeat(64)+"\""+(extra ? ",\"sql\":\"DROP TABLE users\"" : "")+"}}}";
    }
    @Test void publishingRequiresApprovalAndPersistsRealRollbackWithoutModelRewrite() throws Exception {
        var version=version(List.of("publish_remote_release"));
        doReturn("SSH:publish-fixture").when(publishTool).targetEnvironment();
        doReturn("{\"task\":\"PUBLISH_RELEASE\",\"successful\":false,\"exitCode\":47,\"deployed\":false,\"rolledBack\":true}").when(publishTool).execute(any());
        for(var approve : List.of(false,true)) {
            var result=mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON).content(publishBody(version,false))).andExpect(request().asyncStarted()).andReturn();
            String id=null;
            for(int i=0;i<150 && id==null;i++) {
                var ids=jdbc.query("SELECT id FROM approval_request WHERE agent_version_id=:v AND status='PENDING'",Map.of("v",version),(rs,n)->rs.getString("id"));
                if(!ids.isEmpty()) id=ids.getFirst(); else Thread.sleep(20);
            }
            assertThat(id).isNotNull();
            mvc.perform(post("/api/approvals/{id}/"+(approve ? "approve" : "reject"),id).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test\"}")).andExpect(status().isOk());
            var response=mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(response).contains("APPROVAL_REQUEST","APPROVAL_RESULT");
            if(approve) assertThat(response).contains("PUBLISH_RELEASE","rolledBack","原始工具结果");
            else assertThat(response).contains("工具未执行").doesNotContain("PUBLISH_RELEASE");
        }
        verify(publishTool,times(1)).execute(any()); verify(model,never()).complete(any(),any(),any());
    }
    @Test void publishArbitrarySqlCannotBypassGatewayOrCreateApproval() throws Exception {
        var version=version(List.of("publish_remote_release"));
        doReturn("SSH:publish-fixture").when(publishTool).targetEnvironment();
        var result=mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON).content(publishBody(version,true))).andExpect(request().asyncStarted()).andReturn();
        var response=mvc.perform(asyncDispatch(result)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(response).contains("不支持的参数").doesNotContain("event:approval_required");
        verify(publishTool,never()).execute(any());
    }

    @Test void fixedRequestExecutesExactlyOnceWithApprovalAndRawEvidence() throws Exception {
        var response = runWithApproval(true, "{\"successful\":true,\"exitCode\":0,\"schemaSha256\":\"verified\"}");
        assertThat(response).contains("TOOL_RESULT", "verified", "原始工具结果");
        verify(tool, times(1)).execute(any());
        var events = jdbc.queryForList("SELECT event_type FROM audit_event WHERE agent_version_id IN "
                + "(SELECT agent_version_id FROM approval_request WHERE target_environment='SSH:fixed-test-target')", Map.of());
        assertThat(events.toString()).contains("TOOL_EXECUTION_COMPLETED", "APPROVAL_DECIDED");
    }
    @Test void nonZeroResultCannotBeRewrittenAsSuccessfulByModel() throws Exception {
        assertThat(runWithApproval(true, "{\"successful\":false,\"exitCode\":1,\"output\":\"Access denied\"}"))
                .contains("Access denied", "exitCode", "false");
    }
    @Test void rejectedApprovalDoesNotExecuteOrProduceSuccess() throws Exception {
        assertThat(runWithApproval(false, "must-not-appear")).contains("工具未执行").doesNotContain("must-not-appear");
        verify(tool, never()).execute(any());
    }
    @Test void dangerousArgumentsAreRejectedByExistingGatewayBeforeApproval() throws Exception {
        var version = version(List.of("inspect_remote_deployment"));
        var result = mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                .content(body(version, "{\"task\":\"DATABASE_SCHEMA\",\"sql\":\"DROP TABLE users\"}")))
                .andExpect(request().asyncStarted()).andReturn();
        var response = mvc.perform(asyncDispatch(result)).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(response).contains("不支持的参数").doesNotContain("event:approval_required");
        verify(tool, never()).execute(any());
        verify(model, never()).complete(any(),any(),any());
    }
    @Test void unboundFixedToolIsRejectedBeforeCreatingRun() throws Exception {
        mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                .content(body(version(List.of()), "{\"task\":\"DATABASE_SCHEMA\"}")))
                .andExpect(status().isBadRequest());
    }
    @Test void freeChatCannotPresentUnexecutedToolAsDiagnosticEvidence() throws Exception {
        var version=version(List.of("inspect_remote_deployment"));
        when(model.complete(any(),any(),any())).thenReturn(new ModelTurn("编造成功 deploy Access denied", List.of()));
        var result=mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentVersionId\":\""+version+"\",\"message\":\"请使用 inspect_remote_deployment 运行 DATABASE_SCHEMA\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        var response=mvc.perform(asyncDispatch(result)).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        // Model proposal remains in MODEL_CALL history, but cannot become the assistant answer.
        assertThat(response).contains("EXECUTION_EVIDENCE_CHECK", "任务未执行");
        var answers=jdbc.queryForList("SELECT content FROM message WHERE role='assistant' AND conversation_id IN "
                + "(SELECT conversation_id FROM agent_run WHERE agent_version_id=:v)", Map.of("v",version));
        assertThat(answers.toString()).contains("任务未执行").doesNotContain("编造成功");
        verify(tool,never()).execute(any());
    }
    @Test void exhaustedRejectedCallsCannotProduceFabricatedFinalization() throws Exception {
        var version=version(List.of("inspect_remote_deployment"));
        when(model.complete(any(),any(),any())).thenAnswer(invocation -> new ModelTurn("", List.of(
                new ModelToolCall(UUID.randomUUID().toString(), "inspect_remote_deployment", "{\"task\":\"DATABASE_SCHEMA\"}"))));
        var result=mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentVersionId\":\""+version+"\",\"message\":\"请使用 inspect_remote_deployment\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        for (int round=0; round<4; round++) {
            String approvalId=null;
            for (int i=0;i<150 && approvalId==null;i++) {
                var ids=jdbc.query("SELECT id FROM approval_request WHERE agent_version_id=:v AND status='PENDING'",
                        Map.of("v",version),(rs,n)->rs.getString("id"));
                if (!ids.isEmpty()) approvalId=ids.getFirst(); else Thread.sleep(20);
            }
            assertThat(approvalId).isNotNull();
            mvc.perform(post("/api/approvals/{id}/reject",approvalId).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"test\"}")).andExpect(status().isOk());
        }
        var response=mvc.perform(asyncDispatch(result)).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(response).contains("EXECUTION_EVIDENCE_CHECK", "任务未执行").doesNotContain("MODEL_FINALIZATION");
        verify(model,times(4)).complete(any(),any(),any());
        verify(tool,never()).execute(any());
    }
}

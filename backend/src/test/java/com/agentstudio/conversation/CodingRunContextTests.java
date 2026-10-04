package com.agentstudio.conversation;

import static org.assertj.core.api.Assertions.*;
import com.agentstudio.runtime.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class CodingRunContextTests {
    private RunStep step(int n,String type,String tool,String input,String output) {
        return new RunStep("step"+n,"old",n,type,"COMPLETED","call",tool,input,output,null,Instant.now());
    }
    private AgentRun run(String id,String version,String status,List<RunStep> steps) {
        return new AgentRun(id,"same-conversation",version,status,Instant.now(),Instant.now(),"budget",steps);
    }
    @Test void resumesRealWritesAndFailuresWithoutReplayingPatchesOrOldApprovals() {
        var old=run("old","v1","FAILED",List.of(
                step(1,"TOOL_CALL","apply_workspace_text_patch","{\"path\":\"src/Test.java\",\"replacements\":\"DO_NOT_REPLAY\"}",null),
                step(2,"TOOL_RESULT","apply_workspace_text_patch",null,"{\"updated\":true,\"afterSha256\":\"oldhash\"}"),
                step(3,"TOOL_RESULT","run_workspace_verification",null,"{\"task\":\"MAVEN_TEST\",\"successful\":false,\"output\":\"fresh failed test\"}")));
        var context=CodingRunContext.build(List.of(old,run("other-version","v2","FAILED",List.of(step(1,"TOOL_RESULT","current_time",null,"OTHER_VERSION"))),
                run("active","v1","THINKING",List.of(step(1,"TOOL_RESULT","current_time",null,"IN_PROGRESS")))),"current","v1");
        assertThat(context).contains("src/Test.java","updated","fresh failed test","新操作仍须重新审批")
                .doesNotContain("DO_NOT_REPLAY","OTHER_VERSION","IN_PROGRESS");
    }
    @Test void historyIsBoundedAndFinalDiagnosticsSurviveLargeToolOutput() {
        var steps=new ArrayList<RunStep>();
        for(int i=0;i<100;i++)steps.add(step(i,"TOOL_RESULT","run_workspace_verification",null,"start"+"x".repeat(25000)+"failure-"+i));
        assertThat(CodingRunContext.build(List.of(run("old","v1","FAILED",steps)),"current","v1"))
                .hasSizeLessThan(50000).contains("failure-99");
    }
    @Test void modelContextPreservesValidReceiptAndFinalTestFailure()throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var receipt=json.createObjectNode();
        receipt.put("task","MAVEN_TEST").put("successful",false).put("output","start"+"x".repeat(28000)+"FINAL_FAILURE");
        receipt.putArray("reports").addObject().put("suite","fresh.Report").put("failures",1);
        var context=json.readTree(ChatService.toolContext(receipt.toString()));
        assertThat(context.path("successful").asBoolean()).isFalse();assertThat(context.path("output").asText()).endsWith("FINAL_FAILURE");
        assertThat(context.path("reports").get(0).path("suite").asText()).isEqualTo("fresh.Report");
    }
}

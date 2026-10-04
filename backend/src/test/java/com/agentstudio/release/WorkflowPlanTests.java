package com.agentstudio.release;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class WorkflowPlanTests {
    final ObjectMapper json=new ObjectMapper();final Instant now=Instant.parse("2026-10-04T06:00:00Z");
    final String target="fixture-target",source="a".repeat(64),release="20261004T055000Z-1234abcd";
    WorkflowPlan.Evidence fact(String phase,String body)throws Exception{return new WorkflowPlan.Evidence(phase,"SUCCEEDED",json.readTree(body),now.minusSeconds(30));}
    Map<String,WorkflowPlan.Evidence> ready()throws Exception{
        var map=new LinkedHashMap<String,WorkflowPlan.Evidence>();
        map.put("CANDIDATE",fact("CANDIDATE","{\"target\":\"fixture-target\",\"sourceSha256\":\"%s\",\"releaseId\":\"%s\",\"manifestSha256\":\"%s\"}".formatted(source,release,"b".repeat(64))));
        map.put("IMAGE",fact("IMAGE","{\"target\":\"fixture-target\",\"releaseId\":\"%s\",\"manifestSha256\":\"%s\",\"imageId\":\"sha256:%s\"}".formatted(release,"b".repeat(64),"c".repeat(64))));
        map.put("BACKUP",fact("BACKUP","{\"target\":\"fixture-target\",\"backupId\":\"20261004T055000Z-9876abcd\",\"manifestSha256\":\"%s\"}".formatted("d".repeat(64))));
        map.put("SCHEMA",fact("SCHEMA","{\"target\":\"fixture-target\",\"schemaSha256\":\"%s\"}".formatted("e".repeat(64))));
        map.put("HISTORY",fact("HISTORY","{\"target\":\"fixture-target\",\"output\":\"[\\\"1\\\",\\\"BASELINE\\\",1]\"}"));
        map.put("STATUS",fact("STATUS","{\"target\":\"fixture-target\",\"currentImageId\":\"sha256:%s\",\"productionSha256\":\"%s\",\"appHealth\":\"healthy\"}".formatted("f".repeat(64),"1".repeat(64))));return map;
    }
    WorkflowPlan.Next plan(Map<String,WorkflowPlan.Evidence> e){return WorkflowPlan.plan(e,target,source,now,false);}
    @Test void onlyOneNextActionAndServerOwnedEightInputs()throws Exception{
        assertThat(plan(Map.of()).phase()).isEqualTo("CANDIDATE");var p=plan(ready());assertThat(p.phase()).isEqualTo("PUBLISH");assertThat(p.arguments()).hasSize(8);assertThat(p.arguments().get("releaseId")).isEqualTo(release);
    }
    @Test void oldSchemaOnlyRequiresUpdatingSchemaNotCandidateOrBaseline()throws Exception{
        var e=ready();var old=e.get("SCHEMA");e.put("SCHEMA",new WorkflowPlan.Evidence("SCHEMA",old.status(),old.receipt(),now.minusSeconds(301)));assertThat(plan(e).phase()).isEqualTo("SCHEMA");
    }
    @Test void expiredBackupDoesNotDeleteOrRebuildOtherMaterials()throws Exception{
        assertThat(WorkflowPlan.plan(ready(),target,source,now.plusSeconds(1801),false).phase()).isEqualTo("BACKUP");
    }
    @Test void mismatchedSourceTargetAndImageAreBlocked()throws Exception{
        assertThat(WorkflowPlan.plan(ready(),target,"0".repeat(64),now,false).blocked()).isTrue();assertThat(WorkflowPlan.plan(ready(),"other",source,now,false).blocked()).isTrue();
        var e=ready();e.put("IMAGE",fact("IMAGE","{\"target\":\"fixture-target\",\"releaseId\":\"wrong\"}"));assertThat(plan(e).blocked()).isTrue();
    }
    @Test void existingBaselineIsNotRegisteredAgainAndMissingBaselineBlocks()throws Exception{
        assertThat(WorkflowPlan.baseline("[\"1\",\"BASELINE\",1]")).isTrue();var e=ready();e.put("HISTORY",fact("HISTORY","{\"target\":\"fixture-target\",\"output\":\"[\\\"1\\\",\\\"SQL\\\",1]\"}"));assertThat(plan(e).blocked()).isTrue();assertThat(plan(e).reason()).contains("首次接入");
    }
    @Test void unknownPublishingNeverAutomaticallyReplays()throws Exception{
        var e=ready();e.put("PUBLISH",new WorkflowPlan.Evidence("PUBLISH","UNKNOWN",null,now.minusSeconds(10)));assertThat(plan(e).phase()).isEqualTo("RECOVERY_STATUS");
        for(var phase:List.of("RECOVERY_STATUS","RECOVERY_HISTORY","RECOVERY_HEALTH")){var r=e.get(phase.equals("RECOVERY_HISTORY")?"HISTORY":"STATUS");e.put(phase,new WorkflowPlan.Evidence(phase,"SUCCEEDED",r.receipt(),now.minusSeconds(5)));}
        assertThat(plan(e).phase()).isEqualTo("REVIEW");assertThat(WorkflowPlan.plan(e,target,source,now,true).phase()).isEqualTo("SCHEMA");
    }
    @Test void deployedResultRequiresPostChecksAndManualAcceptance()throws Exception{
        var e=ready();e.put("PUBLISH",new WorkflowPlan.Evidence("PUBLISH","DEPLOYED",json.createObjectNode(),now.minusSeconds(10)));assertThat(plan(e).phase()).isEqualTo("POST_STATUS");
        e.put("POST_STATUS",new WorkflowPlan.Evidence("POST_STATUS","SUCCEEDED",json.readTree("{\"target\":\"fixture-target\",\"currentImageId\":\"sha256:%s\",\"appHealth\":\"healthy\"}".formatted("c".repeat(64))),now.minusSeconds(5)));assertThat(plan(e).phase()).isEqualTo("POST_HEALTH");
        e.put("POST_HEALTH",new WorkflowPlan.Evidence("POST_HEALTH","SUCCEEDED",json.readTree("{\"target\":\"fixture-target\"}"),now.minusSeconds(4)));assertThat(plan(e).phase()).isEqualTo("ACCEPT");
    }
    @Test void missingPreparationNeverSkipsUnknownProductionRecovery(){var e=Map.of("PUBLISH",new WorkflowPlan.Evidence("PUBLISH","UNKNOWN",null,now.minusSeconds(10)));assertThat(plan(e).phase()).isEqualTo("RECOVERY_STATUS");}
    @Test void unknownBackupDoesNotCreateAnotherBackup()throws Exception{var e=ready();e.put("BACKUP",new WorkflowPlan.Evidence("BACKUP","UNKNOWN",null,now.minusSeconds(30)));assertThat(plan(e).blocked()).isTrue();assertThat(plan(e).reason()).contains("另一份备份");}
    @Test void unknownReceiptWithCandidateAlreadyOnlineOnlyOffersManualReconciliation()throws Exception{
        var e=ready();e.put("PUBLISH",new WorkflowPlan.Evidence("PUBLISH","UNKNOWN",null,now.minusSeconds(10)));
        for(var phase:List.of("RECOVERY_STATUS","RECOVERY_HISTORY","RECOVERY_HEALTH")){
            var receipt=phase.equals("RECOVERY_HISTORY")?e.get("HISTORY").receipt():json.readTree("{\"target\":\"fixture-target\",\"currentImageId\":\"sha256:%s\",\"appHealth\":\"healthy\"}".formatted("c".repeat(64)));
            e.put(phase,new WorkflowPlan.Evidence(phase,"SUCCEEDED",receipt,now.minusSeconds(5)));
        }
        assertThat(plan(e).phase()).isEqualTo("RECONCILE");assertThat(plan(e).arguments()).isEmpty();assertThat(e.get("PUBLISH").status()).isEqualTo("UNKNOWN");
    }
}

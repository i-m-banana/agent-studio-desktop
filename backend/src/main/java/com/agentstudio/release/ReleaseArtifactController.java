package com.agentstudio.release;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
/** Audit-derived inventory only. No remote inspection or cleanup occurs on GET. */
@RestController @RequestMapping("/api/release-artifacts")
public class ReleaseArtifactController{
    private final ReleaseTaskService tasks;private final JdbcTemplate jdbc;
    public ReleaseArtifactController(ReleaseTaskService tasks,JdbcTemplate jdbc){this.tasks=tasks;this.jdbc=jdbc;}
    public record Artifact(String taskId,String kind,String identity,String target,String status,String sourceSha256,Object observedBytes,List<String> workflows,String retention,String runId){}
    @GetMapping public List<Artifact> list(@RequestParam(defaultValue="100")int limit,@RequestParam(defaultValue="0")int offset){
        var result=new ArrayList<Artifact>();for(var t:tasks.list(null,limit,offset)){
            var r=t.receipt();if(r==null)continue;String kind,identity;
            if(t.toolName().equals("prepare_remote_deployment_backup")){kind="备份";identity=r.path("backupId").asText();}
            else if(t.toolName().equals("build_release_candidate_image")){kind="镜像";identity=r.path("imageId").asText();}
            else if(t.toolName().equals("prepare_release_candidate")){kind="候选";identity=r.path("releaseId").asText();}
            else continue;if(identity.isBlank())continue;
            var refs=jdbc.query("SELECT DISTINCT workflow_id FROM release_workflow_member WHERE run_id=? AND call_id=?",(rs,n)->rs.getString(1),t.runId(),t.sourceStep().toolCallId());
            Object bytes=r.has("artifactBytes")?r.path("artifactBytes").asLong():r.has("databaseBytes")?r.path("databaseBytes").asLong():"未记录";
            result.add(new Artifact(t.id(),kind,identity,t.target(),t.status(),r.path("sourceSha256").asText(),bytes,refs,kind.equals("备份")?"所有备份保留；这里没有删除入口":"保留审计与来源；历史页不推断是否仍在服务器",t.runId()));
        }return result;
    }
}

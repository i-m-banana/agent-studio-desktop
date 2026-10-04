package com.agentstudio.release;

import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class WorkflowStore {
    public boolean projectFrozen(String project){return jdbc.queryForObject("SELECT COUNT(*) FROM release_workflow w JOIN release_workflow_lease l ON l.workflow_id=w.id WHERE w.project_id=?",Integer.class,project)>0;}
    public record Workflow(String id,String projectId,int projectRevision,String workspaceIdentity,String sourceSha256,
        String conversationId,String agentVersionId,String target,String targetKey,String targetIdentity,
        String status,int revision,String activeRunId,String message,boolean businessAccepted,String reviewedPublishCall,Instant createdAt) {
        public Workflow change(String state,String run,String text,boolean accepted) {
            return new Workflow(id,projectId,projectRevision,workspaceIdentity,sourceSha256,conversationId,agentVersionId,
                target,targetKey,targetIdentity,state,revision+1,run,text,accepted,reviewedPublishCall,createdAt);
        }
        public Workflow reviewed(String call){return new Workflow(id,projectId,projectRevision,workspaceIdentity,sourceSha256,conversationId,agentVersionId,target,targetKey,targetIdentity,status,revision,activeRunId,message,businessAccepted,call,createdAt);}
    }
    public record Member(String id,String workflowId,String runId,String callId,String phase,Instant createdAt) {}
    private final JdbcTemplate jdbc; private final ObjectMapper json;
    public WorkflowStore(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    private Workflow decode(String text){try{return json.readValue(text,Workflow.class);}catch(Exception e){throw new IllegalStateException("发布任务记录无法读取",e);}}
    private String encode(Workflow w){try{return json.writeValueAsString(w);}catch(Exception e){throw new IllegalStateException(e);}}
    public Workflow get(String id){return jdbc.query("SELECT record_json FROM release_workflow WHERE id=?",(rs,n)->decode(rs.getString(1)),id).stream().findFirst().orElseThrow(()->new com.agentstudio.system.ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"发布任务不存在"));}
    public List<Workflow> list(String project){return jdbc.query("SELECT record_json FROM release_workflow WHERE project_id=? ORDER BY updated_at DESC LIMIT 100",(rs,n)->decode(rs.getString(1)),project);}
    public List<Workflow> leased(){return jdbc.query("SELECT w.record_json FROM release_workflow w JOIN release_workflow_lease l ON l.workflow_id=w.id",(rs,n)->decode(rs.getString(1)));}
    public String owner(String key){return jdbc.query("SELECT workflow_id FROM release_workflow_lease WHERE target_key=?",(rs,n)->rs.getString(1),key).stream().findFirst().orElse(null);}
    @Transactional public void create(Workflow w){
        jdbc.update("INSERT INTO release_workflow_lease(target_key,workflow_id) VALUES (?,?)",w.targetKey(),w.id());
        jdbc.update("INSERT INTO release_workflow(id,project_id,conversation_id,target_key,record_json,updated_at) VALUES (?,?,?,?,?,?)",w.id(),w.projectId(),w.conversationId(),w.targetKey(),encode(w),java.sql.Timestamp.from(Instant.now()));
    }
    public void save(Workflow w){jdbc.update("UPDATE release_workflow SET record_json=?,updated_at=? WHERE id=?",encode(w),java.sql.Timestamp.from(Instant.now()),w.id());}
    @Transactional public void release(Workflow w){save(w);jdbc.update("DELETE FROM release_workflow_lease WHERE workflow_id=?",w.id());}
    @Transactional public void dispatch(Workflow w,String operation,String run){
        jdbc.update("INSERT INTO release_workflow_operation(operation_id,workflow_id,run_id) VALUES (?,?,?)",operation,w.id(),run);save(w);
    }
    public String operationRun(String id){return jdbc.query("SELECT run_id FROM release_workflow_operation WHERE operation_id=?",(rs,n)->rs.getString(1),id).stream().findFirst().orElse(null);}
    public String workflowForRun(String run){return jdbc.query("SELECT workflow_id FROM release_workflow_operation WHERE run_id=?",(rs,n)->rs.getString(1),run).stream().findFirst().orElse(null);}
    public void member(String flow,String run,String call,String phase){jdbc.update("INSERT INTO release_workflow_member(id,workflow_id,run_id,call_id,phase,created_at) VALUES (?,?,?,?,?,?)",UUID.randomUUID().toString(),flow,run,call,phase,java.sql.Timestamp.from(Instant.now()));}
    public List<Member> members(String flow){return jdbc.query("SELECT * FROM release_workflow_member WHERE workflow_id=? ORDER BY created_at,id",(rs,n)->new Member(rs.getString("id"),flow,rs.getString("run_id"),rs.getString("call_id"),rs.getString("phase"),rs.getTimestamp("created_at").toInstant()),flow);}
}

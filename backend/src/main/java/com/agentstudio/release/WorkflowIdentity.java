package com.agentstudio.release;

import com.agentstudio.project.*;
import com.agentstudio.coding.*;
import com.agentstudio.adapter.ssh.RemoteDeploymentService;
import org.springframework.stereotype.Component;

@Component
public class WorkflowIdentity {
    private final LocalProjectService projects;private final CodingWorkspace workspace;private final RemoteDeploymentService deployment;
    public WorkflowIdentity(LocalProjectService projects,CodingWorkspace workspace,RemoteDeploymentService deployment){this.projects=projects;this.workspace=workspace;this.deployment=deployment;}
    public String target(){return deployment.target(deployment.current());}
    public String targetIdentity(){return deployment.approvalTarget()+"|BACKUP:"+deployment.current().remoteBackupRoot()+"|WORKFLOW_CONTRACT:1";}
    public String workspaceIdentity(String project) throws Exception{return projects.identity(projects.get(project));}
    public String source(String project)throws Exception{try(var c=ProjectExecutionContext.enter(projects.get(project))){return ProjectSourceSnapshot.fingerprint(workspace);}}
    public void validateTarget(WorkflowStore.Workflow flow){if(!targetIdentity().equals(flow.targetIdentity())||!target().equals(flow.target()))throw new IllegalStateException("部署目标或配置已变化；需对原目标进行人工核查，不能把恢复操作发到新目标");}
    public void validate(WorkflowStore.Workflow flow)throws Exception{
        var p=projects.get(flow.projectId());
        if(!projects.status(p).status().equals("READY")||p.revision()!=flow.projectRevision())throw new IllegalStateException("项目保护范围或配置已变化，请结束旧任务并新建发布任务");
        if(!workspaceIdentity(p.id()).equals(flow.workspaceIdentity()) || !source(p.id()).equals(flow.sourceSha256()))throw new IllegalStateException("源码或工作区身份已变化，旧发布任务不能继续；请重新验证预览并新建任务");
        validateTarget(flow);
        if(!java.nio.file.Path.of(deployment.current().localSourceRoot()).toAbsolutePath().normalize().equals(java.nio.file.Path.of(p.sourceRoot())))throw new IllegalStateException("编码根与发布源码根不一致");
    }
    public static String hash(String text){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}

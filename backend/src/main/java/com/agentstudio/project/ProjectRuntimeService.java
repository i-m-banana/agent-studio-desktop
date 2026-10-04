package com.agentstudio.project;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reviewed presets only; never accepts commands, credentials, URLs, or user SQL. */
@Service
public class ProjectRuntimeService {
    public record Configuration(String projectId,int revision,String preset,boolean previewEnabled,boolean mysqlEnabled,int previewMinutes){}
    public record Request(int expectedProjectRevision,int expectedRuntimeRevision,String preset,boolean previewEnabled,boolean mysqlEnabled,int previewMinutes,boolean isolationConfirmed){}
    private final LocalProjectService projects;private final JdbcTemplate jdbc;private final ObjectMapper json;
    public ProjectRuntimeService(LocalProjectService projects,JdbcTemplate jdbc,ObjectMapper json){this.projects=projects;this.jdbc=jdbc;this.json=json;}
    public Configuration get(String project){projects.get(project);return jdbc.query("SELECT record_json FROM project_runtime WHERE project_id=?",(rs,n)->{try{return json.readValue(rs.getString(1),Configuration.class);}catch(Exception e){throw new IllegalStateException(e);}},project).stream().findFirst().orElse(new Configuration(project,0,"SHIGUANGXV_SPRING_BOOT_H2_V1",true,true,30));}
    @Transactional public synchronized Configuration save(String project,Request r)throws Exception{
        var old=get(project);var p=projects.get(project);projects.requireNoActiveRun(project);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM local_preview WHERE project_id=? AND state NOT IN ('STOPPED','FAILED')",Integer.class,project)>0)throw new IllegalStateException("先停止项目的隔离预览，再调整运行设置");
        if(r.expectedRuntimeRevision()!=old.revision()||r.expectedProjectRevision()!=p.revision())throw new IllegalStateException("项目或运行配置已变化，请刷新后保存");
        if(!r.isolationConfirmed())throw new IllegalArgumentException("请确认合成数据、隔离网络与资源限制说明");
        if(!"SHIGUANGXV_SPRING_BOOT_H2_V1".equals(r.preset())||r.previewMinutes()<5||r.previewMinutes()>30)throw new IllegalArgumentException("仅支持已审查的时光序 Spring Boot/H2 预设，预览时限 5–30 分钟");
        var next=new Configuration(project,old.revision()+1,r.preset(),r.previewEnabled(),r.mysqlEnabled(),r.previewMinutes());
        // Project revision invalidates every old session/approval/workflow, not just a browser checkbox.
        projects.save(project,new LocalProjectService.Request(p.name(),p.sourceRoot(),p.writableDirectories(),p.protectedDirectories(),p.revision(),p.protectionConfirmed()));
        jdbc.update("DELETE FROM project_runtime WHERE project_id=?",project);jdbc.update("INSERT INTO project_runtime(project_id,record_json) VALUES (?,?)",project,json.writeValueAsString(next));return next;
    }
    public Configuration requirePreview(String project){var c=get(project);if(!c.previewEnabled())throw new IllegalStateException("项目运行配置关闭了隔离预览");return c;}
    public Configuration requireMysql(String project){var c=get(project);if(!c.mysqlEnabled())throw new IllegalStateException("项目运行配置关闭了 MySQL 集成验证");return c;}
}

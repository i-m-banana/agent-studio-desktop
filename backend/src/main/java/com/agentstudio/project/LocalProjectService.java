package com.agentstudio.project;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import com.agentstudio.coding.CodingWorkspace;
import com.agentstudio.system.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LocalProjectService {
    public record Request(String name, String sourceRoot, List<String> writableDirectories, List<String> protectedDirectories,
                          Integer expectedRevision, boolean protectionConfirmed) {}
    public record Status(LocalProject project, String status, String message) {}
    public record Binding(String projectId, int revision, String workspaceIdentity) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final List<Path> allowedRoots;
    private final List<Path> protectedRoots;

    public LocalProjectService(JdbcTemplate jdbc, ObjectMapper mapper,
            @Value("${agent-studio.projects.allowed-roots:}") String roots,
            @Value("${agent-studio.projects.protected-roots:}") String protectedRoots) {
        this.jdbc = jdbc; this.mapper = mapper;
        this.allowedRoots = configuredPaths(roots); this.protectedRoots = configuredPaths(protectedRoots);
    }
    private List<Path> configuredPaths(String text) {
        return Arrays.stream(text.split(";")).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> Path.of(s).toAbsolutePath().normalize()).toList();
    }
    public List<String> allowedRoots() { return allowedRoots.stream().filter(this::narrowRoot).map(Path::toString).toList(); }
    public List<Status> list() {
        return records().stream().map(this::status).toList();
    }
    private List<LocalProject> records() { return jdbc.query("SELECT configuration_json FROM local_project ORDER BY id", (rs,row)->decode(rs.getString(1))); }
    public LocalProject get(String id) {
        return jdbc.query("SELECT configuration_json FROM local_project WHERE id=?", (rs, row) -> decode(rs.getString(1)), id)
                .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "本地项目不存在"));
    }
    private LocalProject decode(String value) {
        try { return mapper.readValue(value, LocalProject.class); }
        catch (Exception e) { throw new IllegalStateException("本地项目记录无法读取", e); }
    }
    public synchronized LocalProject save(String id, Request request) {
        if (request.name() == null || request.name().isBlank() || request.name().length() > 120 || request.name().matches("(?s).*[|\\p{Cntrl}].*"))
            throw new IllegalArgumentException("项目名称必须为 1 到 120 字符");
        var root = authorizedRoot(request.sourceRoot());
        var old = id == null ? null : get(id);
        if(records().stream().anyMatch(p -> !p.archived() && !p.id().equals(id) && (Path.of(p.sourceRoot()).startsWith(root) || root.startsWith(Path.of(p.sourceRoot())))))
            throw new IllegalArgumentException("该源码目录已有重叠项目登记，请编辑现有项目，不要重复授权");
        if (old != null) {
            if (!Objects.equals(request.expectedRevision(), old.revision())) conflict("项目配置已变化，请刷新后再保存");
            requireIdle(id);
            requireNoWorkflow(id);
        }
        var writable = paths(request.writableDirectories(), false);
        var protectedPaths = paths(request.protectedDirectories(), true);
        protectedPaths.addAll(List.of("data","mysql-data","uploads","backups","logs",".secrets"));
        for (var protectedRoot : protectedRoots) {
            if (root.startsWith(protectedRoot)) throw new IllegalArgumentException("项目位于受保护目录内");
            if (protectedRoot.startsWith(root)) protectedPaths.add(root.relativize(protectedRoot).toString().replace('\\','/'));
        }
        var project = new LocalProject(old == null ? UUID.randomUUID().toString() : id, request.name().trim(), root.toString(),
                List.copyOf(writable), protectedPaths.stream().distinct().toList(), old == null ? 1 : old.revision() + 1,
                old != null && old.archived(), request.protectionConfirmed());
        // No filesystem creation here. Missing roots remain visible with an actionable status.
        persist(project, old == null);
        return project;
    }
    private List<String> paths(List<String> values, boolean allowEmpty) {
        if (values == null || values.size() > 40 || (!allowEmpty && values.isEmpty()))
            throw new IllegalArgumentException("请设置允许修改的源码/测试子目录（最多 40 项）");
        var result = new ArrayList<String>();
        for (var value : values) {
            var path = CodingWorkspace.safeRelative(value);
            if (path.toString().isEmpty() || path.toString().equals(".")) throw new IllegalArgumentException("必须填写具体子目录，不能授权整个项目写入");
            result.add(path.toString().replace('\\','/'));
        }
        return result;
    }
    private boolean narrowRoot(Path root) {
        var name = root.getFileName() == null ? "" : root.getFileName().toString().toLowerCase(Locale.ROOT);
        return root.getNameCount() >= 2 && !Set.of("idea_work", "projects", "users", "windows", "program files", "programdata").contains(name)
                && !root.equals(Path.of(System.getProperty("user.home")).toAbsolutePath().normalize());
    }
    private Path authorizedRoot(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("请选择具体项目目录");
        var path = Path.of(value);
        if (!path.isAbsolute()) throw new IllegalArgumentException("项目根目录必须是绝对路径");
        path = path.normalize();
        if (!narrowRoot(path) || !allowedRoots.contains(path))
            throw new IllegalArgumentException("目录未获得服务器授权；只能选择允许清单内的具体项目，不能开放整个盘或项目父目录");
        return path;
    }
    public String identity(LocalProject project) throws Exception {
        var path = authorizedRoot(project.sourceRoot());
        var real = CodingWorkspace.requireUnredirectedDirectory(path);
        if (protectedRoots.stream().anyMatch(real::startsWith)) throw new IllegalArgumentException("工作区位于受保护目录");
        var attributes = Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return real + "#" + CodingWorkspace.directoryIdentity(real) + "#" + attributes.creationTime() + "#POLICY:" + protectedRoots.hashCode();
    }
    public Status status(LocalProject project) {
        if (project.archived()) return new Status(project, "ARCHIVED", "已归档，源码和历史记录保留");
        if (!project.protectionConfirmed()) return new Status(project, "NEEDS_PROTECTION", "请先确认真实数据保护范围，编码和测试暂未开放");
        try {
            var root=CodingWorkspace.requireUnredirectedDirectory(authorizedRoot(project.sourceRoot()));
            if(protectedRoots.stream().anyMatch(root::startsWith))throw new IllegalArgumentException("工作区位于受保护目录");
            return new Status(project,"READY","目录可用；编码需新建绑定会话，验证在隔离副本中运行");
        }
        catch (Exception e) { return new Status(project, "UNAVAILABLE", e.getMessage()); }
    }
    public Binding binding(String conversationId) {
        return jdbc.query("SELECT local_project_id,local_project_revision,local_workspace_identity FROM conversation WHERE id=?",
                (rs, row) -> rs.getString(1) == null ? null : new Binding(rs.getString(1), rs.getInt(2), rs.getString(3)), conversationId)
                .stream().filter(Objects::nonNull).findFirst().orElse(null);
    }
    public synchronized void bind(String conversationId, String projectId) throws Exception {
        if (projectId == null || projectId.isBlank()) return;
        var project = get(projectId);
        if (project.archived()) conflict("项目已归档，请选择其他项目");
        if (!project.protectionConfirmed()) conflict("请先确认真实数据目录保护范围");
        var identity = identity(project);
        if (jdbc.update("UPDATE conversation SET local_project_id=?,local_project_revision=?,local_workspace_identity=? WHERE id=? AND local_project_id IS NULL",
                project.id(), project.revision(), identity, conversationId) != 1) conflict("会话不能重新绑定，请新建会话");
    }
    public LocalProject resolve(String conversationId, String requestedProject) throws Exception {
        var binding = binding(conversationId);
        if (binding == null) {
            if (requestedProject != null && !requestedProject.isBlank()) conflict("旧会话尚未绑定项目，请选择项目后新建会话");
            return null;
        }
        if (requestedProject != null && !requestedProject.isBlank() && !binding.projectId().equals(requestedProject))
            conflict("会话已绑定其他项目，请新建会话");
        var project = get(binding.projectId());
        if (!project.protectionConfirmed() || project.archived() || binding.revision() != project.revision() || !binding.workspaceIdentity().equals(identity(project)))
            conflict("项目配置或目录已变化，请确认新配置并新建会话；旧会话保留查看");
        return project;
    }
    public synchronized LocalProject archive(String id, boolean archived) {
        requireIdle(id); var old = get(id);
        requireNoWorkflow(id);
        if(!archived && records().stream().anyMatch(p -> !p.archived() && !p.id().equals(id) && (Path.of(p.sourceRoot()).startsWith(Path.of(old.sourceRoot())) || Path.of(old.sourceRoot()).startsWith(Path.of(p.sourceRoot())))))
            throw new IllegalArgumentException("已有重叠目录的可用项目，不能恢复重复授权");
        var next = new LocalProject(old.id(), old.name(), old.sourceRoot(), old.writableDirectories(), old.protectedDirectories(), old.revision()+1, archived, old.protectionConfirmed());
        persist(next, false); return next;
    }
    private void requireIdle(String id) {
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM agent_run r JOIN conversation c ON c.id=r.conversation_id WHERE c.local_project_id=? AND r.status NOT IN ('COMPLETED','FAILED','CANCELLED','TIMED_OUT','INTERRUPTED')", Integer.class, id);
        if (active != null && active > 0) conflict("该项目有任务运行中，结束后再修改配置或归档");
    }
    public void requireNoActiveRun(String id) { requireIdle(id); }
    private void requireNoWorkflow(String id){if(jdbc.queryForObject("SELECT COUNT(*) FROM release_workflow w JOIN release_workflow_lease l ON l.workflow_id=w.id WHERE w.project_id=?",Integer.class,id)>0)conflict("该项目已有发布任务，先结束任务再修改配置或归档");}
    private void persist(LocalProject value, boolean create) {
        try {
            var json = mapper.writeValueAsString(value);
            if (create) jdbc.update("INSERT INTO local_project(id,configuration_json,revision,archived) VALUES(?,?,?,?)", value.id(), json, value.revision(), value.archived());
            else jdbc.update("UPDATE local_project SET configuration_json=?,revision=?,archived=? WHERE id=?", json, value.revision(), value.archived(), value.id());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    private void conflict(String message) { throw new ApiException(HttpStatus.CONFLICT, message); }
}

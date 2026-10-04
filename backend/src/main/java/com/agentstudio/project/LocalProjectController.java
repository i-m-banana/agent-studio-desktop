package com.agentstudio.project;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/local-projects")
public class LocalProjectController {
    private final LocalProjectService projects;
    @org.springframework.beans.factory.annotation.Autowired private com.agentstudio.coding.IsolatedProjectRunner isolated;
    public LocalProjectController(LocalProjectService projects) { this.projects = projects; }
    @GetMapping public List<LocalProjectService.Status> list() { return projects.list(); }
    @GetMapping("/allowed-roots") public Map<String,List<String>> roots() { return Map.of("roots", projects.allowedRoots()); }
    @GetMapping("/sandbox") public Map<String,Object> sandbox() { return isolated.status(); }
    @PostMapping public LocalProject create(@RequestBody LocalProjectService.Request request) { return projects.save(null, request); }
    @PutMapping("/{id}") public LocalProject update(@PathVariable String id, @RequestBody LocalProjectService.Request request) { return projects.save(id, request); }
    @PostMapping("/{id}/check") public LocalProjectService.Status check(@PathVariable String id) { return projects.status(projects.get(id)); }
    @PostMapping("/{id}/archive") public LocalProject archive(@PathVariable String id) { return projects.archive(id, true); }
    @PostMapping("/{id}/restore") public LocalProject restore(@PathVariable String id) { return projects.archive(id, false); }
}

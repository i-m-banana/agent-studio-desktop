package com.agentstudio.coding;
import java.util.*;
import com.agentstudio.project.LocalProjectService;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/local-projects/{project}/mysql-verifications")
public class MySqlVerificationController {
    private final MySqlVerificationRunner runner;private final LocalProjectService projects;
    public MySqlVerificationController(MySqlVerificationRunner runner,LocalProjectService projects){this.runner=runner;this.projects=projects;}
    @GetMapping public List<Map<String,Object>> list(@PathVariable String project){projects.get(project);return runner.list(project);}
    @PostMapping("/{id}/cleanup") public void cleanup(@PathVariable String project,@PathVariable String id)throws Exception{projects.requireNoActiveRun(project);runner.cleanup(project,id);}
}

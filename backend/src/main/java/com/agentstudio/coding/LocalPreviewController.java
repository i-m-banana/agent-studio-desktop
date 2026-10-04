package com.agentstudio.coding;

import java.util.*;
import com.agentstudio.project.LocalProjectService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/local-previews")
public class LocalPreviewController {
    private final LocalPreviewService previews;private final LocalProjectService projects;
    public LocalPreviewController(LocalPreviewService previews,LocalProjectService projects){this.previews=previews;this.projects=projects;}
    @GetMapping public List<LocalPreviewService.Preview> list(@RequestParam String projectId){projects.get(projectId);return previews.list(projectId);}
    // Human-only resource disposal, not a code/command execution endpoint. Start only via the approval gateway.
    @PostMapping(value="/{id}/stop",consumes="application/json") public Map<String,Boolean> stop(@PathVariable String id)throws Exception{previews.stop(id);return Map.of("stopped",true);}
}

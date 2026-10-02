package com.agentstudio.release;

import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/release-tasks")
public class ReleaseTaskController {
    private final ReleaseTaskService service;
    public ReleaseTaskController(ReleaseTaskService service) { this.service = service; }
    @GetMapping
    List<ReleaseTaskService.Task> list(@RequestParam(required = false) String conversationId,
                                      @RequestParam(defaultValue = "100") int limit,
                                      @RequestParam(defaultValue = "0") int offset) {
        return service.list(conversationId, limit, offset);
    }
}

package com.agentstudio.execution;

import java.util.List;

import com.agentstudio.runtime.RunRepository;
import com.agentstudio.system.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit-events")
public class AuditController {
    private final AuditRepository audits;
    private final RunRepository runs;

    public AuditController(AuditRepository audits, RunRepository runs) {
        this.audits = audits;
        this.runs = runs;
    }

    @GetMapping
    List<AuditEvent> list(@RequestParam String runId, @RequestParam(defaultValue = "100") int limit) {
        runs.find(runId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "运行记录不存在"));
        return audits.list(runId, limit);
    }
}

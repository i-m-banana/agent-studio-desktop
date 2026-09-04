package com.agentstudio.runtime;

import com.agentstudio.system.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunRepository runs;

    public RunController(RunRepository runs) {
        this.runs = runs;
    }

    @GetMapping("/{id}")
    AgentRun get(@PathVariable String id) {
        return runs.find(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "运行记录不存在"));
    }
}

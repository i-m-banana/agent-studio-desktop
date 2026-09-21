package com.agentstudio.adapter.ssh;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ssh/deployment")
public class RemoteDeploymentController {
    private final RemoteDeploymentService service;
    public RemoteDeploymentController(RemoteDeploymentService service) { this.service = service; }

    @GetMapping RemoteDeploymentProfile status() { return service.status(); }
    @PutMapping RemoteDeploymentProfile save(@Valid @RequestBody RemoteDeploymentRequest request) {
        return service.save(request);
    }
    @PostMapping("/test") SshConnectionTestResult test() { return service.test(); }
}

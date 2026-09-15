package com.agentstudio.adapter.ssh;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ssh/workspace")
public class SshWorkspaceController {
    private final SshWorkspaceService service;
    public SshWorkspaceController(SshWorkspaceService service) { this.service = service; }
    @GetMapping SshWorkspaceStatus status() { return service.status(); }
    @PutMapping SshWorkspaceStatus save(@Valid @RequestBody SshWorkspaceRequest request) { return service.save(request); }
    @PostMapping("/fingerprint") SshFingerprint fingerprint(@Valid @RequestBody SshFingerprintRequest request) {
        return service.inspect(request);
    }
    @PostMapping("/test") SshConnectionTestResult test() { return service.test(); }
}

package com.agentstudio.adapter.ssh;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/ssh/workspace")
public class SshWorkspaceController {
    private final SshWorkspaceService service;
    private final SshWorkspaceBrowserService browser;
    public SshWorkspaceController(SshWorkspaceService service, SshWorkspaceBrowserService browser) {
        this.service = service; this.browser = browser;
    }
    @GetMapping SshWorkspaceStatus status() { return service.status(); }
    @PutMapping SshWorkspaceStatus save(@Valid @RequestBody SshWorkspaceRequest request) { return service.save(request); }
    @PostMapping("/fingerprint") SshFingerprint fingerprint(@Valid @RequestBody SshFingerprintRequest request) {
        return service.inspect(request);
    }
    @PostMapping("/test") SshConnectionTestResult test() { return service.test(); }
    @GetMapping("/browser/directory") JsonNode directory(
            @RequestParam(defaultValue = ".") String path,
            @RequestParam(defaultValue = "100") int maxEntries) {
        return browser.directory(path, maxEntries);
    }
    @GetMapping("/browser/file") JsonNode file(
            @RequestParam String path,
            @RequestParam(defaultValue = "1") int startLine,
            @RequestParam(defaultValue = "200") int maxLines) {
        return browser.file(path, startLine, maxLines);
    }
}

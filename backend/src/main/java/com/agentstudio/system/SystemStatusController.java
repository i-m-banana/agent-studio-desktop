package com.agentstudio.system;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemStatusController {

    private final SystemReadinessService readiness;

    public SystemStatusController(SystemReadinessService readiness) {
        this.readiness = readiness;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "application", "agent-studio-backend",
                "version", BuildVersion.VALUE,
                "status", "UP",
                "timestamp", Instant.now().toString());
    }

    @GetMapping("/readiness")
    public SystemReadiness readiness() {
        return readiness.inspect();
    }
}

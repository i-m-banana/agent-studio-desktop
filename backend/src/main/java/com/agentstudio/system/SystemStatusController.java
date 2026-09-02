package com.agentstudio.system;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemStatusController {

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "application", "agent-studio-backend",
                "status", "UP",
                "timestamp", Instant.now().toString());
    }
}


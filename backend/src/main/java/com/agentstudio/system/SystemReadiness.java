package com.agentstudio.system;

import java.time.Instant;
import java.util.List;

public record SystemReadiness(
        String application,
        String version,
        String status,
        Instant timestamp,
        List<ReadinessCheck> checks) {
}

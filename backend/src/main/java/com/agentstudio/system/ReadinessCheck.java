package com.agentstudio.system;

public record ReadinessCheck(
        String id,
        String name,
        String status,
        String detail,
        String action,
        boolean required) {
}

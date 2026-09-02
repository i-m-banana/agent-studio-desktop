package com.agentstudio.model;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ModelProfileRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 40) String provider,
        @NotBlank @Size(max = 500) @Pattern(regexp = "https?://.+", message = "必须是 http 或 https 地址") String baseUrl,
        @NotBlank @Size(max = 160) String modelName,
        @NotBlank @Size(max = 160) @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*", message = "必须是合法环境变量名") String apiKeyEnv,
        @DecimalMin("0.0") @DecimalMax("2.0") BigDecimal temperature) {

    public BigDecimal effectiveTemperature() {
        return temperature == null ? new BigDecimal("0.7") : temperature;
    }
}


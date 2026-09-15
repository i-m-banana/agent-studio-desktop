package com.agentstudio.adapter.ssh;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SshFingerprintRequest(@NotBlank @Size(max = 255) String host,
                                    @Min(1) @Max(65535) int port) {}

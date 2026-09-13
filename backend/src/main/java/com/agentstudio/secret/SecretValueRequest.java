package com.agentstudio.secret;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SecretValueRequest(@NotBlank @Size(max = 16000) String value) {}

package com.agentstudio.secret;

import java.util.Optional;

import org.springframework.stereotype.Component;

@Component
public class SecretResolver {
    private final SecretStore store;

    public SecretResolver(SecretStore store) { this.store = store; }

    /** Environment variables deliberately win so deployments can override desktop credentials. */
    public Optional<String> resolve(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        var environment = System.getenv(name);
        if (environment != null && !environment.isBlank()) return Optional.of(environment);
        return store.read(name).filter(value -> !value.isBlank());
    }

    public String source(String name) {
        var environment = System.getenv(name);
        if (environment != null && !environment.isBlank()) return "ENVIRONMENT";
        return store.read(name).filter(value -> !value.isBlank()).isPresent() ? "SECURE_STORE" : "NONE";
    }
}

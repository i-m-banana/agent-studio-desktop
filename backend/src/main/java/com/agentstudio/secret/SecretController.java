package com.agentstudio.secret;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/secrets")
public class SecretController {
    private final SecretService service;
    public SecretController(SecretService service) { this.service = service; }

    @GetMapping
    List<SecretStatus> list() { return service.list(); }

    @PutMapping("/{name}")
    SecretStatus put(@PathVariable String name, @Valid @RequestBody SecretValueRequest request) {
        return service.put(name, request.value());
    }

    @DeleteMapping("/{name}")
    ResponseEntity<Void> delete(@PathVariable String name) {
        service.delete(name);
        return ResponseEntity.noContent().build();
    }
}

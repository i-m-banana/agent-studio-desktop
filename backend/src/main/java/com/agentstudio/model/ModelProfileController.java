package com.agentstudio.model;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/models")
public class ModelProfileController {

    private final ModelProfileService service;

    public ModelProfileController(ModelProfileService service) {
        this.service = service;
    }

    @GetMapping
    List<ModelProfile> list() {
        return service.list();
    }

    @PostMapping
    ResponseEntity<ModelProfile> create(@Valid @RequestBody ModelProfileRequest request) {
        var created = service.create(request);
        return ResponseEntity.created(URI.create("/api/models/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    ModelProfile update(@PathVariable String id, @Valid @RequestBody ModelProfileRequest request) {
        return service.update(id, request);
    }
}

package com.agentstudio.agent;

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
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService service;

    public AgentController(AgentService service) {
        this.service = service;
    }

    @GetMapping
    List<AgentDefinition> list() {
        return service.list();
    }

    @PostMapping
    ResponseEntity<AgentDefinition> create(@Valid @RequestBody AgentDefinitionRequest request) {
        var created = service.create(request);
        return ResponseEntity.created(URI.create("/api/agents/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    AgentDefinition update(@PathVariable String id, @Valid @RequestBody AgentDefinitionRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/publish")
    AgentVersion publish(@PathVariable String id) {
        return service.publish(id);
    }

    @GetMapping("/{id}/versions")
    List<AgentVersion> versions(@PathVariable String id) {
        return service.versions(id);
    }
}

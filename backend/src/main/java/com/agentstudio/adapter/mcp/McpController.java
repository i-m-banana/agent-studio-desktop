package com.agentstudio.adapter.mcp;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequestMapping("/api/mcp/servers")
public class McpController {
    private final McpService service;

    public McpController(McpService service) { this.service = service; }

    @GetMapping
    List<McpServer> list() { return service.listServers(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    McpServer create(@Valid @RequestBody McpServerRequest request) { return service.create(request); }

    @PostMapping("/{id}/sync")
    McpSyncResult sync(@PathVariable String id) { return service.sync(id); }
}

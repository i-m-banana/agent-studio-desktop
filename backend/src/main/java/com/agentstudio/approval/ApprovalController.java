package com.agentstudio.approval;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {
    private final ApprovalService service;

    public ApprovalController(ApprovalService service) { this.service = service; }

    @PostMapping("/{id}/approve")
    ApprovalRequest approve(@PathVariable String id,
                            @Valid @RequestBody(required = false) ApprovalDecisionRequest request) {
        return service.decide(id, true, request == null ? null : request.reason());
    }

    @PostMapping("/{id}/reject")
    ApprovalRequest reject(@PathVariable String id,
                           @Valid @RequestBody(required = false) ApprovalDecisionRequest request) {
        return service.decide(id, false, request == null ? null : request.reason());
    }
}

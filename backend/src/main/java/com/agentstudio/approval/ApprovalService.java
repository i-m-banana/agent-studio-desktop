package com.agentstudio.approval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.agentstudio.model.ModelToolCall;
import com.agentstudio.system.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ApprovalService {
    private final ApprovalRepository repository;
    private final Duration timeout;
    private final ConcurrentHashMap<String, CompletableFuture<ApprovalOutcome>> waiters = new ConcurrentHashMap<>();

    public ApprovalService(ApprovalRepository repository,
                           @Value("${agent-studio.runtime.approval-timeout:90s}") Duration timeout) {
        this.repository = repository;
        this.timeout = timeout;
    }

    public ApprovalRequest request(String runId, ModelToolCall call) {
        var now = Instant.now();
        var request = new ApprovalRequest(UUID.randomUUID().toString(), runId, call.id(), call.name(),
                call.argumentsJson(), sha256(call.argumentsJson()), "PENDING", null, now, now.plus(timeout), null);
        waiters.put(request.id(), new CompletableFuture<>());
        try {
            repository.insert(request);
            return request;
        } catch (RuntimeException exception) {
            waiters.remove(request.id());
            throw exception;
        }
    }

    public ApprovalOutcome await(ApprovalRequest request) throws Exception {
        try {
            var remaining = Math.max(1, Duration.between(Instant.now(), request.expiresAt()).toMillis());
            var outcome = waiters.get(request.id()).get(remaining, TimeUnit.MILLISECONDS);
            if (outcome.approved() && !repository.consume(request.id(), request.argumentsSha256())) {
                throw new IllegalStateException("审批参数校验失败或审批已被消费");
            }
            return outcome;
        } catch (java.util.concurrent.TimeoutException exception) {
            repository.decide(request.id(), "EXPIRED", "审批超时", Instant.now());
            return new ApprovalOutcome("EXPIRED", "审批超时");
        } finally {
            waiters.remove(request.id());
        }
    }

    public ApprovalRequest decide(String id, boolean approved, String reason) {
        var current = repository.find(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "审批请求不存在"));
        if (!"PENDING".equals(current.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "审批请求已处理");
        }
        if (!current.expiresAt().isAfter(Instant.now())) {
            repository.decide(id, "EXPIRED", "审批超时", Instant.now());
            complete(id, new ApprovalOutcome("EXPIRED", "审批超时"));
            throw new ApiException(HttpStatus.CONFLICT, "审批请求已过期");
        }
        var status = approved ? "APPROVED" : "REJECTED";
        var normalizedReason = reason == null || reason.isBlank() ? null : reason.trim();
        if (!repository.decide(id, status, normalizedReason, Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "审批请求已处理");
        }
        complete(id, new ApprovalOutcome(status, normalizedReason));
        return repository.find(id).orElseThrow();
    }

    public void cancelPending(String runId, String reason) {
        repository.findPendingByRun(runId).forEach(request -> {
            if (repository.decide(request.id(), "CANCELLED", reason, Instant.now())) {
                complete(request.id(), new ApprovalOutcome("CANCELLED", reason));
            }
        });
    }

    public void expireAllPending(String reason) {
        repository.findAllPending().forEach(request ->
                repository.decide(request.id(), "EXPIRED", reason, Instant.now()));
    }

    private void complete(String id, ApprovalOutcome outcome) {
        var waiter = waiters.get(id);
        if (waiter != null) waiter.complete(outcome);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}

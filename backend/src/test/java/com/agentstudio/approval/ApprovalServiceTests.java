package com.agentstudio.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.agentstudio.model.ModelToolCall;
import com.agentstudio.tool.WriteWorkspaceNoteTool;
import org.junit.jupiter.api.Test;

class ApprovalServiceTests {
    @Test
    void approvedArgumentsAreConsumedExactlyAgainstTheirHash() throws Exception {
        var repository = mock(ApprovalRepository.class);
        var service = new ApprovalService(repository, Duration.ofSeconds(2));
        var request = service.request("run-1", "conversation-1", "version-1",
                new ModelToolCall("call-1", "write_workspace_note", "{\"fileName\":\"a.md\",\"content\":\"ok\"}"),
                new WriteWorkspaceNoteTool("../data").descriptor(), "LOCAL");
        when(repository.find(request.id())).thenReturn(Optional.of(request));
        when(repository.decide(eq(request.id()), eq("APPROVED"), any(), any())).thenReturn(true);
        when(repository.consume(request.id(), request.argumentsSha256())).thenReturn(true);

        var outcomeFuture = CompletableFuture.supplyAsync(() -> {
            try { return service.await(request); }
            catch (Exception exception) { throw new RuntimeException(exception); }
        });
        service.decide(request.id(), true, "reviewed");

        assertThat(outcomeFuture.get()).extracting(ApprovalOutcome::status, ApprovalOutcome::reason)
                .containsExactly("APPROVED", "reviewed");
        verify(repository).consume(request.id(), request.argumentsSha256());
    }
}

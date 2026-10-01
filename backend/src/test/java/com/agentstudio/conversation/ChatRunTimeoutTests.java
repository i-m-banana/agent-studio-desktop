package com.agentstudio.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatRunTimeoutTests {
    @Test void evidenceGuardDoesNotBlockToolExplanationQuestions() {
        assertThat(ChatService.requestsToolExecution("请实际调用 inspect_remote_deployment", List.of("inspect_remote_deployment"))).isTrue();
        assertThat(ChatService.requestsToolExecution("请解释如何使用 inspect_remote_deployment", List.of("inspect_remote_deployment"))).isFalse();
        assertThat(ChatService.requestsToolExecution("请使用未绑定工具", List.of("inspect_remote_deployment"))).isFalse();
    }
    @Test void toolHistoryKeepsBoundedJsonAndFinalDiagnostics() {
        var json = "{\"output\":\"" + "x".repeat(16000) + "\",\"exitCode\":1,\"outputTruncated\":false}";
        assertThat(ChatService.toolRecord(json)).isEqualTo(json);
        assertThat(ChatService.toolRecord("start" + "x".repeat(140000) + "unexpected EOF"))
                .startsWith("start").endsWith("unexpected EOF").hasSizeLessThan(129000);
    }
    @Test
    void candidateVersionGetsMatchingSseAndRunBudgetWithoutChangingOrdinaryRuns() {
        var service = service(Duration.ofSeconds(120));
        assertThat(service.runTimeout(List.of("prepare_release_candidate"))).isEqualTo(Duration.ofSeconds(900));
        assertThat(service.runTimeout(List.of("build_release_candidate_image"))).isEqualTo(Duration.ofSeconds(1200));
        assertThat(service.runTimeout(List.of("publish_remote_release"))).isEqualTo(Duration.ofSeconds(1200));
        assertThat(service.runTimeout(List.of("inspect_remote_deployment"))).isEqualTo(Duration.ofSeconds(120));
        assertThat(service.runTimeout(List.of())).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void preservesLongerUserConfiguredBudget() {
        assertThat(service(Duration.ofSeconds(1800)).runTimeout(List.of("build_release_candidate_image")))
                .isEqualTo(Duration.ofSeconds(1800));
        assertThat(service(Duration.ofSeconds(1200)).runTimeout(List.of("prepare_release_candidate")))
                .isEqualTo(Duration.ofSeconds(1200));
    }

    private ChatService service(Duration total) {
        return new ChatService(null, null, null, null, null, null, null, null, null, null,
                4, 4, total, Duration.ofSeconds(900));
    }
}

package com.agentstudio.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatRunTimeoutTests {
    @Test
    void candidateVersionGetsMatchingSseAndRunBudgetWithoutChangingOrdinaryRuns() {
        var service = service(Duration.ofSeconds(120));
        assertThat(service.runTimeout(List.of("prepare_release_candidate"))).isEqualTo(Duration.ofSeconds(900));
        assertThat(service.runTimeout(List.of("inspect_remote_deployment"))).isEqualTo(Duration.ofSeconds(120));
        assertThat(service.runTimeout(List.of())).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void preservesLongerUserConfiguredBudget() {
        assertThat(service(Duration.ofSeconds(1200)).runTimeout(List.of("prepare_release_candidate")))
                .isEqualTo(Duration.ofSeconds(1200));
    }

    private ChatService service(Duration total) {
        return new ChatService(null, null, null, null, null, null, null, null, null, null,
                4, 4, total, Duration.ofSeconds(900));
    }
}

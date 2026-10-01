package com.agentstudio.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class AuditRepositoryTests {
    @Test void auditTruncationIncludesEllipsisWithinDatabaseLimit() {
        assertThat(AuditRepository.truncate(null)).isNull();
        for (int size : new int[] {999, 1000, 1001, 16000}) {
            var text = "错".repeat(size);
            var result = AuditRepository.truncate(text);
            assertThat(result.length()).isLessThanOrEqualTo(1000);
            if (size <= 1000) assertThat(result).isEqualTo(text);
            else assertThat(result).hasSize(1000).endsWith("…");
        }
        assertThat(AuditRepository.truncate("x".repeat(998) + "😀" + "more"))
                .isEqualTo("x".repeat(998) + "…");
    }

    @Test void longFailureIsBoundedBeforeJdbcAndKeepsAuditIdentity() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var event = new AuditRepository(jdbc).add("run", "conversation", "version",
                "TOOL_EXECUTION_FAILED", "build_release_candidate_image", "EXECUTE", "HIGH",
                "FAILED", "a".repeat(64), "registry unexpected EOF\n" + "错".repeat(16000));
        var params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertThat(params.getValue().getValue("details")).isEqualTo(event.details());
        assertThat(event.details()).hasSize(1000).startsWith("registry unexpected EOF").endsWith("…");
        assertThat(params.getValue().getValue("argumentsSha256")).isEqualTo("a".repeat(64));
        assertThat(params.getValue().getValue("status")).isEqualTo("FAILED");
    }
}

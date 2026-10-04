package com.agentstudio.conversation;

import static org.assertj.core.api.Assertions.*;
import com.agentstudio.runtime.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelReconnectTests {
    @Test void onlyPreConnectionFailuresAreRetryable() {
        assertThat(ModelConnectionFailure.retryable(new java.net.http.HttpConnectTimeoutException("connect"))).isTrue();
        assertThat(ModelConnectionFailure.retryable(new RuntimeException(new java.net.ConnectException("refused")))).isTrue();
        assertThat(ModelConnectionFailure.retryable(new java.net.http.HttpTimeoutException("response timeout"))).isFalse();
        assertThat(ModelConnectionFailure.retryable(new java.io.IOException("HTTP 401"))).isFalse();
        assertThat(ModelConnectionFailure.retryable(new InterruptedException())).isFalse();
    }
    @Test void preChangeTestsDoNotBecomeValidationOfLaterWrites() {
        var time=Instant.now();
        var steps=List.of(new RunStep("test","r",1,"TOOL_RESULT","COMPLETED","a","run_workspace_verification",null,
                "{\"task\":\"MAVEN_TEST\",\"successful\":true,\"exitCode\":0}",1L,time),
                new RunStep("write","r",2,"TOOL_RESULT","COMPLETED","b","apply_workspace_text_patch",null,
                        "{\"path\":\"src/Item.java\",\"updated\":true}",1L,time));
        assertThat(InterruptedCodingProgress.summarize(new AgentRun("r","c","v","FAILED",time,time,"connect",steps),"连接失败"))
                .contains("src/Item.java","随后还有文件写入","尚未完成重新验证","逐项审批");
    }
}

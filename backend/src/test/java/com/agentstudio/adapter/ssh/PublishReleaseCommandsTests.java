package com.agentstudio.adapter.ssh;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PublishReleaseCommandsTests {
    @Test void windowsAndMixedLineEndingsBecomeExecutablePosixText() {
        var template = "\uFEFFset -eu\r\numask 077\ntrap finish EXIT\rprintf '%s\\n' @IMAGE@\r\n";
        assertThat(PublishReleaseCommands.posixTemplate(template))
                .isEqualTo("set -eu\numask 077\ntrap finish EXIT\nprintf '%s\\n' @IMAGE@\n")
                .doesNotContain("\r", "\uFEFF");
    }

    @Test void posixTextAndShellEscapesRemainUnchanged() {
        var template = "set -eu\nprintf '%s\\n' 'literal $value'\n";
        assertThat(PublishReleaseCommands.posixTemplate(template)).isEqualTo(template);
        assertThat(PublishReleaseCommands.posixTemplate(PublishReleaseCommands.posixTemplate(template)))
                .isEqualTo(template);
    }
}

package com.agentstudio.adapter.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import com.agentstudio.secret.SecretResolver;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SftpSessionFactoryTests {
    @TempDir Path root;

    @Test
    void acknowledgedHeartbeatsKeepSilentSessionsAliveBeyondIdleDeadline() throws Exception {
        try (var server = SshServer.setUpDefaultServer()) {
            server.setPort(0);
            var keys = new SimpleGeneratorHostKeyProvider(root.resolve("host-key.ser"));
            server.setKeyPairProvider(keys);
            server.setPasswordAuthenticator((username, password, session) -> "tester".equals(username) && "password".equals(password));
            CoreModuleProperties.IDLE_TIMEOUT.set(server, Duration.ofMillis(600));
            server.start();
            var secrets = mock(SecretResolver.class);
            when(secrets.resolve("TEST_PASSWORD")).thenReturn(Optional.of("password"));
            var properties = new SshWorkspaceProperties("127.0.0.1", server.getPort(), "tester", "/workspace",
                    KeyUtils.getFingerPrint(BuiltinDigests.sha256, keys.loadKeys(null).iterator().next().getPublic()),
                    "TEST_PASSWORD", Duration.ofSeconds(5));
            var factory = new SftpSessionFactory(secrets, Duration.ofMillis(100));
            factory.executeSession(properties, session -> {
                CoreModuleProperties.IDLE_TIMEOUT.set(session, Duration.ofMillis(600));
                assertThat(CoreModuleProperties.HEARTBEAT_REPLY_WAIT.getRequired(session)).isEqualTo(Duration.ofSeconds(10));
                assertThat(CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.getRequired(session)).isEqualTo(3);
                Thread.sleep(2200); // Beyond both idle deadline and the server's timer sweep.
                assertThat(session.isOpen()).isTrue();
                assertThat(session.isClosing()).isFalse();
                return null;
            });
        }
    }
}

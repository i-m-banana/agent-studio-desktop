package com.agentstudio.adapter.ssh;

import java.util.concurrent.atomic.AtomicReference;

import com.agentstudio.secret.SecretResolver;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.password.PasswordIdentityProvider;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.stereotype.Component;

@Component
public class SftpSessionFactory {
    private final SecretResolver secrets;

    public SftpSessionFactory(SecretResolver secrets) { this.secrets = secrets; }

    public <T> T execute(SshWorkspaceProperties properties, Operation<T> operation) throws Exception {
        return executeSession(properties, session -> {
            try (var sftp = SftpClientFactory.instance().createSftpClient(session)) {
                return operation.apply(sftp);
            }
        });
    }

    public <T> T executeSession(SshWorkspaceProperties properties, SessionOperation<T> operation) throws Exception {
        properties.validate();
        var password = secrets.resolve(properties.passwordSecret())
                .orElseThrow(() -> new IllegalStateException(
                        "SSH 密码凭据不存在，请在安全凭据中保存 " + properties.passwordSecret()));
        var observedFingerprint = new AtomicReference<String>();
        try (var client = SshClient.setUpDefaultClient()) {
            client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
            client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
            client.setPasswordIdentityProvider(PasswordIdentityProvider.EMPTY_PASSWORDS_PROVIDER);
            client.setServerKeyVerifier((session, address, serverKey) -> {
                var actual = KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey);
                observedFingerprint.set(actual);
                return constantTimeEquals(properties.hostKeySha256(), actual);
            });
            client.start();
            try (var session = client.connect(properties.username(), properties.host(), properties.port())
                    .verify(properties.connectTimeout()).getSession()) {
                session.addPasswordIdentity(password);
                session.auth().verify(properties.connectTimeout());
                return operation.apply(session);
            } catch (Exception exception) {
                var actual = observedFingerprint.get();
                if (actual != null && !constantTimeEquals(properties.hostKeySha256(), actual)) {
                    throw new SecurityException("SSH 主机指纹不匹配，已拒绝连接；实际指纹为 " + actual, exception);
                }
                throw exception;
            }
        }
    }

    public SshFingerprint inspectFingerprint(String host, int port, java.time.Duration timeout) throws Exception {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("SSH 主机不能为空");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("SSH 端口必须在 1 到 65535 之间");
        var observed = new AtomicReference<SshFingerprint>();
        try (var client = SshClient.setUpDefaultClient()) {
            client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
            client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
            client.setPasswordIdentityProvider(PasswordIdentityProvider.EMPTY_PASSWORDS_PROVIDER);
            client.setServerKeyVerifier((session, address, key) -> {
                observed.set(new SshFingerprint(host.trim(), port, KeyUtils.getKeyType(key),
                        KeyUtils.getFingerPrint(BuiltinDigests.sha256, key),
                        "请在云厂商控制台或服务器管理员处独立核对，确认一致后再保存"));
                return true;
            });
            client.start();
            try (var session = client.connect("agent-studio-fingerprint-probe", host.trim(), port)
                    .verify(timeout).getSession()) {
                try {
                    // TCP connect alone does not guarantee that SSH key exchange has completed.
                    // Starting authentication forces host-key verification; no password or key
                    // identity is configured for this probe, so no credential can be sent.
                    session.auth().verify(timeout);
                } catch (Exception expectedWithoutIdentity) {
                    if (observed.get() == null) throw expectedWithoutIdentity;
                }
                var result = observed.get();
                if (result == null) throw new IllegalStateException("服务器未提供可识别的主机公钥");
                return result;
            }
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    public interface Operation<T> { T apply(SftpClient client) throws Exception; }

    @FunctionalInterface
    public interface SessionOperation<T> { T apply(ClientSession session) throws Exception; }
}

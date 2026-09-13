package com.agentstudio.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretStoreTests {
    @TempDir java.nio.file.Path temporaryDirectory;

    @Test
    void protectsRoundTripsAndDeletesASecretForTheCurrentWindowsUser() throws Exception {
        var store = new SecretStore(temporaryDirectory.toString());
        if (!store.supported()) return;
        var clearText = "secret-that-must-not-appear-on-disk";

        store.write("TEST_DESKTOP_SECRET", clearText);

        assertThat(store.read("TEST_DESKTOP_SECRET")).contains(clearText);
        try (var files = Files.walk(temporaryDirectory)) {
            assertThat(files.filter(Files::isRegularFile).toList()).allSatisfy(path ->
                    assertThat(Files.readString(path, java.nio.charset.StandardCharsets.ISO_8859_1))
                            .doesNotContain(clearText));
        }
        assertThat(store.delete("TEST_DESKTOP_SECRET")).isTrue();
        assertThat(store.read("TEST_DESKTOP_SECRET")).isEmpty();
    }
}

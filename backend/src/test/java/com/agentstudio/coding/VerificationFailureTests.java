package com.agentstudio.coding;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class VerificationFailureTests {
    @Test void identifiesOfflineResolutionFailure() {
        assertThat(VerificationFailure.missingOfflineDependency("Cannot access central in offline mode and artifact has not been downloaded before")).isTrue();
    }
    @Test void compilationAndTestFailuresAreNotCacheFailures() {
        assertThat(VerificationFailure.missingOfflineDependency("[ERROR] Tests run: 3, Failures: 1")).isFalse();
        assertThat(VerificationFailure.missingOfflineDependency("[ERROR] cannot find symbol")).isFalse();
        assertThat(VerificationFailure.missingOfflineDependency(null)).isFalse();
    }
}

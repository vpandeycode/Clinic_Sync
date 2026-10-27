package org.clinicsync.common.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackoffPolicyTest {

    @Test
    @DisplayName("Delay bound grows exponentially and then caps at maxDelayMs")
    void delayBoundGrowsAndCaps() {
        BackoffPolicy policy = new BackoffPolicy(100, 1_000, () -> 1.0);

        assertThat(policy.delayBound(1)).isEqualTo(100);
        assertThat(policy.delayBound(2)).isEqualTo(200);
        assertThat(policy.delayBound(3)).isEqualTo(400);
        assertThat(policy.delayBound(4)).isEqualTo(800);
        assertThat(policy.delayBound(5)).isEqualTo(1_000);
        assertThat(policy.delayBound(50)).isEqualTo(1_000);
    }

    @Test
    @DisplayName("Jitter keeps the actual delay inside [bound/2, bound]")
    void jitterStaysWithinBounds() {
        BackoffPolicy lowJitter = new BackoffPolicy(100, 10_000, () -> 0.0);
        BackoffPolicy highJitter = new BackoffPolicy(100, 10_000, () -> 1.0);

        assertThat(lowJitter.delayFor(3)).isEqualTo(200);   // bound 400 -> half
        assertThat(highJitter.delayFor(3)).isEqualTo(400);  // bound 400 -> full
    }

    @Test
    @DisplayName("Random jitter never leaves the [bound/2, bound] window")
    void randomJitterStaysInWindow() {
        BackoffPolicy policy = new BackoffPolicy(50, 5_000);
        for (int attempt = 1; attempt <= 12; attempt++) {
            long bound = policy.delayBound(attempt);
            long delay = policy.delayFor(attempt);
            assertThat(delay).isBetween(bound / 2, bound);
        }
    }

    @Test
    @DisplayName("Invalid configuration is rejected")
    void invalidConfigurationRejected() {
        assertThatThrownBy(() -> new BackoffPolicy(0, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(200, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(100, 200, null)).isInstanceOf(NullPointerException.class);
    }
}

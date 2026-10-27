package org.clinicsync.common.hlc;

import net.jqwik.api.*;
import org.clinicsync.common.exception.ClockDriftException;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HlcPropertyTest {

    @Property
    void localEventsAreStrictlyMonotonic(@ForAll("timestampSequences") long[] timestamps) {
        AtomicLong physicalClock = new AtomicLong(0L);
        HybridLogicalClock hlcEngine = new HybridLogicalClock("node-1", physicalClock::get, 300_000L);

        Hlc previous = null;
        for (long pt : timestamps) {
            physicalClock.set(pt);
            Hlc current = hlcEngine.now();

            if (previous != null) {
                assertThat(current).isGreaterThan(previous);
            }
            previous = current;
        }
    }

    @Property
    void remoteUpdatePreservesCausality(
            @ForAll("validTimestamps") long localPt,
            @ForAll("validTimestamps") long remoteL,
            @ForAll("validCounters") int remoteC
    ) {
        long maxDrift = 300_000L;
        // Ensure remote timestamp is within drift bound
        long boundedRemoteL = Math.min(remoteL, localPt + maxDrift - 1000L);

        HybridLogicalClock hlcEngine = new HybridLogicalClock("node-local", () -> localPt, maxDrift);
        Hlc remote = new Hlc(boundedRemoteL, remoteC, "node-remote");

        Hlc updated = hlcEngine.update(remote);

        assertThat(updated).isGreaterThan(remote);
        assertThat(updated.l()).isGreaterThanOrEqualTo(Math.max(localPt, boundedRemoteL));
    }

    @Property
    void excessiveFutureDriftIsRejected(
            @ForAll("validTimestamps") long localPt,
            @ForAll("driftExcesses") long excessMs
    ) {
        long maxDrift = 300_000L;
        long driftedRemoteL = localPt + maxDrift + excessMs + 1L;

        HybridLogicalClock hlcEngine = new HybridLogicalClock("node-local", () -> localPt, maxDrift);
        Hlc driftedRemote = new Hlc(driftedRemoteL, 0, "node-drifted");

        assertThatThrownBy(() -> hlcEngine.update(driftedRemote))
                .isInstanceOf(ClockDriftException.class)
                .hasMessageContaining("Remote clock drift exceeds tolerance");
    }

    @Provide
    Arbitrary<long[]> timestampSequences() {
        return Arbitraries.longs().between(1_000_000L, 2_000_000L)
                .array(long[].class).ofMinSize(5).ofMaxSize(50);
    }

    @Provide
    Arbitrary<Long> validTimestamps() {
        return Arbitraries.longs().between(1_000_000L, 2_000_000L);
    }

    @Provide
    Arbitrary<Integer> validCounters() {
        return Arbitraries.integers().between(0, 10_000);
    }

    @Provide
    Arbitrary<Long> driftExcesses() {
        return Arbitraries.longs().between(1L, 1_000_000L);
    }
}

package org.clinicsync.common.hlc;

import org.clinicsync.common.exception.ClockDriftException;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Thread-safe Hybrid Logical Clock (HLC) engine.
 * Ensures strictly monotonic, causally consistent timestamps even under physical clock drift.
 */
public class HybridLogicalClock {

    public static final long DEFAULT_MAX_DRIFT_MS = 5 * 60 * 1000L; // 5 minutes

    private final String nodeId;
    private final LongSupplier physicalTimeSupplier;
    private final long maxDriftMs;

    private long l;
    private int c;

    public HybridLogicalClock(String nodeId) {
        this(nodeId, System::currentTimeMillis, DEFAULT_MAX_DRIFT_MS);
    }

    public HybridLogicalClock(String nodeId, LongSupplier physicalTimeSupplier, long maxDriftMs) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.physicalTimeSupplier = Objects.requireNonNull(physicalTimeSupplier, "physicalTimeSupplier must not be null");
        this.maxDriftMs = maxDriftMs;
        this.l = 0L;
        this.c = 0;
    }

    /**
     * Advances the clock on a local event.
     */
    public synchronized Hlc now() {
        long pt = physicalTimeSupplier.getAsLong();
        if (pt > l) {
            l = pt;
            c = 0;
        } else {
            c++;
        }
        return new Hlc(l, c, nodeId);
    }

    /**
     * Updates the clock upon receiving a remote HLC timestamp.
     * Enforces the maximum physical clock drift bound.
     */
    public synchronized Hlc update(Hlc remote) {
        Objects.requireNonNull(remote, "remote HLC must not be null");
        long pt = physicalTimeSupplier.getAsLong();

        if (remote.l() - pt > maxDriftMs) {
            throw new ClockDriftException(
                    "Remote clock drift exceeds tolerance: remote.l=" + remote.l() + ", local.pt=" + pt + ", maxDrift=" + maxDriftMs
            );
        }

        long lPrime = Math.max(Math.max(l, remote.l()), pt);

        if (lPrime == l && lPrime == remote.l()) {
            c = Math.max(c, remote.c()) + 1;
        } else if (lPrime == l) {
            c = c + 1;
        } else if (lPrime == remote.l()) {
            c = remote.c() + 1;
        } else {
            c = 0;
        }
        l = lPrime;

        return new Hlc(l, c, nodeId);
    }

    public synchronized Hlc current() {
        return new Hlc(l, c, nodeId);
    }
}

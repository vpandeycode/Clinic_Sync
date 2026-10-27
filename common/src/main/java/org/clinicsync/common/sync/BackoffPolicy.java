package org.clinicsync.common.sync;

import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with jitter for sync retries.
 *
 * <p>Attempt {@code n} (1-based) waits for {@code min(base * 2^(n-1), max)} milliseconds, then
 * applies "decorrelated-ish" jitter: the actual delay is picked uniformly from
 * {@code [delay/2, delay]} so retry storms from many clinics spread out instead of syncing in
 * lock-step when connectivity returns.</p>
 *
 * <p>The jitter source is injectable so tests can assert deterministic bounds.</p>
 */
public final class BackoffPolicy {

    private final long baseDelayMs;
    private final long maxDelayMs;
    private final DoubleSupplier jitter;

    public BackoffPolicy(long baseDelayMs, long maxDelayMs) {
        this(baseDelayMs, maxDelayMs, Math::random);
    }

    public BackoffPolicy(long baseDelayMs, long maxDelayMs, DoubleSupplier jitter) {
        if (baseDelayMs <= 0) {
            throw new IllegalArgumentException("baseDelayMs must be positive");
        }
        if (maxDelayMs < baseDelayMs) {
            throw new IllegalArgumentException("maxDelayMs must be >= baseDelayMs");
        }
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.jitter = Objects.requireNonNull(jitter, "jitter must not be null");
    }

    /** Upper bound (without jitter) for the given 1-based attempt, capped at {@code maxDelayMs}. */
    public long delayBound(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        long delay = baseDelayMs;
        for (int i = 1; i < attempt; i++) {
            if (delay >= maxDelayMs / 2) {
                return maxDelayMs;
            }
            delay *= 2;
        }
        return Math.min(delay, maxDelayMs);
    }

    /** Actual delay for the attempt: uniformly in {@code [bound/2, bound]}. */
    public long delayFor(int attempt) {
        long bound = delayBound(attempt);
        long half = bound / 2;
        double roll = Math.min(1.0, Math.max(0.0, jitter.getAsDouble()));
        return half + (long) ((bound - half) * roll);
    }
}

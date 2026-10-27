package org.clinicsync.common.util;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * High-performance RFC 9562 compliant UUIDv7 generator.
 * Provides time-ordered, 128-bit collision-free decentralized identifiers.
 */
public final class UuidV7Generator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static long lastTimestampMs = -1L;
    private static int sequenceCounter = 0;

    private UuidV7Generator() {}

    /**
     * Generates a new time-ordered UUIDv7.
     */
    public static synchronized UUID generate() {
        long currentTimestampMs = System.currentTimeMillis();

        if (currentTimestampMs <= lastTimestampMs) {
            sequenceCounter++;
            currentTimestampMs = lastTimestampMs;
        } else {
            lastTimestampMs = currentTimestampMs;
            sequenceCounter = RANDOM.nextInt(0x3FF); // 10-bit random seed
        }

        long mostSigBits = (currentTimestampMs << 16) | (0x7000L) | (sequenceCounter & 0x0FFFL);

        long randB = RANDOM.nextLong();
        long leastSigBits = (0x8000000000000000L) | (randB & 0x3FFFFFFFFFFFFFFFL);

        return new UUID(mostSigBits, leastSigBits);
    }

    /**
     * Extracts the timestamp in milliseconds from a UUIDv7.
     */
    public static long extractTimestampMs(UUID uuid) {
        return uuid.getMostSignificantBits() >>> 16;
    }
}

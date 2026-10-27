package org.clinicsync.common.hlc;

import java.io.Serializable;
import java.util.Objects;

/**
 * Immutable Hybrid Logical Clock (HLC) timestamp.
 * Encapsulates:
 * - l: Physical timestamp in milliseconds
 * - c: Monotonic logical counter
 * - nodeId: Deterministic tie-breaker identifier
 *
 * Implements total ordering consistent with causality (happens-before).
 */
public record Hlc(long l, int c, String nodeId) implements Comparable<Hlc>, Serializable {

    public Hlc {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        if (l < 0) {
            throw new IllegalArgumentException("Physical time l must be non-negative");
        }
        if (c < 0) {
            throw new IllegalArgumentException("Logical counter c must be non-negative");
        }
    }

    public static Hlc initial(String nodeId) {
        return new Hlc(0L, 0, nodeId);
    }

    public static Hlc parse(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("HLC string must not be blank");
        }
        String[] parts = s.split(":", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid HLC format, expected '<l>:<c>:<nodeId>', got: " + s);
        }
        try {
            long l = Long.parseLong(parts[0]);
            int c = Integer.parseInt(parts[1]);
            String nodeId = parts[2];
            return new Hlc(l, c, nodeId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Failed to parse HLC numbers from: " + s, e);
        }
    }

    @Override
    public int compareTo(Hlc other) {
        if (this.l != other.l) {
            return Long.compare(this.l, other.l);
        }
        if (this.c != other.c) {
            return Integer.compare(this.c, other.c);
        }
        return this.nodeId.compareTo(other.nodeId);
    }

    @Override
    public String toString() {
        return l + ":" + c + ":" + nodeId;
    }
}

package com.emporia.events.math;

import java.util.UUID;

/**
 * High-performance, zero-allocation representation of a 128-bit identifier (UUID)
 * stored as a pair of primitive {@code long} values:
 * {@code mostSigBits} and {@code leastSigBits}.
 *
 * <p>Avoids heap allocation during hot-path event routing, binary serialization,
 * and hash table lookups. Boundary conversion methods {@link #fromUuid(UUID)} and
 * {@link #toUuid()} provide compatibility with legacy APIs.
 */
public record LongPair(long mostSigBits, long leastSigBits) implements Comparable<LongPair> {

    /** Constant representing a zero / null 128-bit identifier. */
    public static final LongPair ZERO = new LongPair(0L, 0L);

    public static LongPair of(long mostSigBits, long leastSigBits) {
        return new LongPair(mostSigBits, leastSigBits);
    }

    public static LongPair fromUuid(UUID uuid) {
        if (uuid == null) return ZERO;
        return new LongPair(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
    }

    public UUID toUuid() {
        if (isZero()) return null;
        return new UUID(mostSigBits, leastSigBits);
    }

    public boolean isZero() {
        return mostSigBits == 0L && leastSigBits == 0L;
    }

    @Override
    public int compareTo(LongPair other) {
        int cmp = Long.compare(this.mostSigBits, other.mostSigBits);
        return cmp != 0 ? cmp : Long.compare(this.leastSigBits, other.leastSigBits);
    }

    @Override
    public String toString() {
        return isZero() ? "00000000-0000-0000-0000-000000000000" : toUuid().toString();
    }
}

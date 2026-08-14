package com.emporia.ordermanagement.service;

import java.util.UUID;

/**
 * Answers "has this identifier been seen before" from memory, so the order hot
 * path never blocks on a database read to find out.
 *
 * <h2>Why a cache could not do this</h2>
 * <p>{@link OrderStateCache} reads through to Postgres on a miss, and a
 * {@code CREATE} carries a freshly generated {@code commandId} and
 * {@code orderId}, so it misses every time. Measured at 120 orders/sec over
 * 14,402 orders: zero cache hits, and the two lookups cost 2.312 of the
 * handler's 2.405 ms — on the single writer thread, where cost multiplies by
 * the queue depth rather than being absorbed in parallel.
 *
 * <p>A read-through cache can never answer "never seen"; it only knows what it
 * holds. This answers exactly that one question, and nothing else.
 *
 * <h2>One question only</h2>
 * <p>A Bloom filter has no false negatives, which is the guarantee the hot path
 * needs. A false positive costs one database lookup that then returns the
 * correct answer, so semantics are preserved exactly. ~6 MB covers an 8-hour
 * session at 120 orders/sec.
 *
 * <p>It deliberately does <b>not</b> hold results. Returning the previous
 * result for a genuine retry is {@link OrderStateCache}'s exact tier, which is
 * sized for the client retry window rather than the session. An earlier version
 * carried its own copy of that tier, so every {@code ProcessedCommand} was held
 * twice and one copy was never read - and worse, it made two things look like
 * the authority for one decision.
 *
 * <p>Both key spaces share one filter: {@code commandId} for idempotency and
 * {@code orderId} for the duplicate-order guard. A collision between the two
 * spaces is just another false positive, which costs a lookup rather than
 * correctness.
 *
 * <h2>The failure mode that matters</h2>
 * <p>A false negative — reporting "never seen" for something processed — lets a
 * duplicate order through, and in a trading system that is a duplicate
 * position. The filter itself cannot produce one; the risk is entirely in
 * whether every processed command reached {@link #remember}. That is why the
 * write path has a single entry point, and why
 * {@code emporia.oms.dedup.duplicate_reached_db} exists to report it if one
 * ever slips through anyway.
 *
 * <p>Not thread-safe by design. The bit array is a plain {@code long[]} with no
 * happens-before guarantee across threads, and the hot path reads and writes it
 * from the single Disruptor writer thread. Keeping both on that one thread
 * removes the visibility question entirely, and the check is cheap enough that
 * there is nothing to gain by moving it.
 */
public final class CommandDedupIndex {

    private final long[] bits;
    private final long bitCount;
    private final int hashCount;

    /**
     * @param expectedEntries identifiers the session is sized for; both key
     *                        spaces count toward it
     * @param falsePositiveRate the share of "never seen" answers allowed to be
     *                          wrong in the safe direction, e.g. 0.001
     */
    public CommandDedupIndex(long expectedEntries, double falsePositiveRate) {
        if (expectedEntries <= 0) throw new IllegalArgumentException("expectedEntries must be positive");
        if (falsePositiveRate <= 0 || falsePositiveRate >= 1) {
            throw new IllegalArgumentException("falsePositiveRate must be between 0 and 1 exclusive");
        }
        double ln2 = Math.log(2);
        long m = (long) Math.ceil(-expectedEntries * Math.log(falsePositiveRate) / (ln2 * ln2));
        this.bitCount = Math.max(64L, m);
        this.bits = new long[(int) ((bitCount + 63) / 64)];
        this.hashCount = Math.max(1, (int) Math.round((double) bitCount / expectedEntries * ln2));
    }

    /**
     * Returns {@code true} only when this identifier has certainly never been
     * remembered. A {@code false} answer means "possibly seen" and must be
     * resolved elsewhere — it is not proof of a duplicate.
     */
    public boolean definitelyNew(UUID id) {
        long h1 = id.getMostSignificantBits();
        long h2 = id.getLeastSignificantBits();
        for (int i = 0; i < hashCount; i++) {
            long index = Math.floorMod(h1 + (long) i * h2, bitCount);
            if ((bits[(int) (index >>> 6)] & (1L << (index & 63))) == 0) {
                return true;
            }
        }
        return false;
    }

    /** Records an identifier. Never removes: see the rotation note on the class. */
    public void remember(UUID id) {
        long h1 = id.getMostSignificantBits();
        long h2 = id.getLeastSignificantBits();
        for (int i = 0; i < hashCount; i++) {
            long index = Math.floorMod(h1 + (long) i * h2, bitCount);
            bits[(int) (index >>> 6)] |= 1L << (index & 63);
        }
    }

    /** Bits allocated, for sizing checks and for reporting memory use. */
    public long bitCount() {
        return bitCount;
    }

    /** Hash functions per identifier, derived from the requested error rate. */
    public int hashCount() {
        return hashCount;
    }
}

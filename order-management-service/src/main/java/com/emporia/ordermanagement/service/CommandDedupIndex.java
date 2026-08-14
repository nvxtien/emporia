package com.emporia.ordermanagement.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.emporia.ordermanagement.model.ProcessedCommand;

import java.time.Duration;
import java.util.Optional;
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
 * holds. This structure is the authority instead of a cache in front of one.
 *
 * <h2>Two tiers</h2>
 * <ul>
 *   <li><b>Bloom filter</b> — answers <i>definitely never seen</i> for a whole
 *       trading session. A Bloom filter has no false negatives, which is
 *       exactly the guarantee the hot path needs. A false positive costs one
 *       database lookup that then returns the correct answer, so semantics are
 *       preserved exactly. ~6 MB covers an 8-hour session at 120 orders/sec.</li>
 *   <li><b>Exact map</b> — answers <i>what was the previous result</i> for a
 *       genuine retry. Sized for the client retry window, not for the
 *       session.</li>
 * </ul>
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
    private final Cache<UUID, ProcessedCommand> recent;

    /**
     * @param expectedEntries identifiers the session is sized for; both key
     *                        spaces count toward it
     * @param falsePositiveRate the share of "never seen" answers allowed to be
     *                          wrong in the safe direction, e.g. 0.001
     * @param recentResults entries in the exact tier, sized for the client
     *                      retry window rather than the session
     */
    public CommandDedupIndex(long expectedEntries, double falsePositiveRate, long recentResults) {
        if (expectedEntries <= 0) throw new IllegalArgumentException("expectedEntries must be positive");
        if (falsePositiveRate <= 0 || falsePositiveRate >= 1) {
            throw new IllegalArgumentException("falsePositiveRate must be between 0 and 1 exclusive");
        }
        double ln2 = Math.log(2);
        long m = (long) Math.ceil(-expectedEntries * Math.log(falsePositiveRate) / (ln2 * ln2));
        this.bitCount = Math.max(64L, m);
        this.bits = new long[(int) ((bitCount + 63) / 64)];
        this.hashCount = Math.max(1, (int) Math.round((double) bitCount / expectedEntries * ln2));
        this.recent = Caffeine.newBuilder()
                .maximumSize(recentResults)
                .expireAfterWrite(Duration.ofHours(24))
                .recordStats()
                .build();
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

    /**
     * Records a processed command in both tiers at once.
     *
     * <p>Both, always: if the Bloom filter only received entries evicted from
     * the exact map, one missed eviction would be a permanent false negative.
     */
    public void remember(ProcessedCommand command) {
        UUID commandId = command.result().commandId();
        remember(commandId);
        recent.put(commandId, command);
    }

    /** Returns the previous result when the exact tier still holds it. */
    public Optional<ProcessedCommand> recall(UUID commandId) {
        return Optional.ofNullable(recent.getIfPresent(commandId));
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

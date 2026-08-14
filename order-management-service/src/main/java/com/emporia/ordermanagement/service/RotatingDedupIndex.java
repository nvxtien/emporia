package com.emporia.ordermanagement.service;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Holds the deduplication filters and forgets in bounded steps, so the hot path
 * can answer "never seen" from memory without the memory growing forever.
 *
 * <h2>The problem this solves</h2>
 * <p>A Bloom filter cannot delete. A single filter on a process that never
 * restarts therefore fills until every answer is a false positive - measured as
 * a design property, not a bug: ~0.1% at eight hours, ~15% at three days, ~50%
 * at a week. Nothing breaks, the hot path simply drifts back to Postgres, and
 * no short benchmark can see it happen.
 *
 * <h2>Why rotation is enough, and a periodic reload is not needed</h2>
 * <p>The obvious fix - drop the filter and reload the window from Postgres -
 * costs a multi-million row read on a schedule, and opens a race between the
 * query and the swap. Neither is necessary: the live filter, once rotated out,
 * <i>is</i> the history for the period it covered. Rotation is a reference
 * shuffle and touches no database.
 *
 * <h2>Why this cannot lose an identifier</h2>
 * <p>The writer thread reads {@code current} once, then writes bits into that
 * snapshot's live filter. If rotation happens in between, the bits land in the
 * filter that just became the newest retained generation - which is still
 * consulted. There is no window in which a write is dropped. Rotation and the
 * history handoff are synchronized against each other because they run on
 * different background threads; the writer thread never takes the lock, it only
 * reads one volatile.
 *
 * <h2>The horizon this creates</h2>
 * <p>{@code retained} generations each cover one rotation interval, so an
 * identifier stays known for at least {@code horizon}. Past that the filters
 * report "never seen" and {@link OrderStateCache} returns that answer
 * <b>without consulting Postgres</b> - a false negative with respect to the
 * database, not merely a slower path. The horizon is therefore a correctness
 * parameter, and it is set to match the Idempotency-Key TTL promised to
 * callers: beyond that TTL a repeated key is contractually a new request rather
 * than a retry.
 *
 * <p>The order-id key space has a second guard that does not expire, because
 * strategy parents can outlive any window: {@link DedupIndexLoader} loads every
 * working order regardless of age.
 *
 * <p>If rotation is ever wrong, the symptom is a duplicate reaching the
 * database, and {@code emporia.oms.dedup.duplicate_reached_db} counts exactly
 * that. It is the oracle for this class.
 */
public final class RotatingDedupIndex {

    private final int generations;
    private final long expectedEntries;
    private final long entriesPerGeneration;
    private final double falsePositiveRate;
    private final Duration horizon;
    private final Duration rotateInterval;

    /**
     * One immutable snapshot so the hot path reads a consistent set of filters
     * from a single volatile read. Replacing it wholesale is what gives the
     * writer thread a happens-before edge onto filters built elsewhere.
     */
    private record Filters(CommandDedupIndex live,
                           List<CommandDedupIndex> retained,
                           @Nullable CommandDedupIndex history,
                           boolean ready) { }

    private volatile Filters current;
    /** Guarded by {@code this}; counts down the startup history's usefulness. */
    private int rotationsSinceHistory;

    /**
     * @param horizon how far back an identifier stays known from memory
     * @param generations filters retained behind the live one; the rotation
     *                    interval is {@code horizon / generations}
     * @param expectedEntries identifiers expected across the whole horizon, both
     *                        key spaces counted
     * @param falsePositiveRate share of "never seen" answers allowed to be wrong
     *                          in the safe direction
     */
    public RotatingDedupIndex(Duration horizon, int generations, long expectedEntries, double falsePositiveRate) {
        if (generations < 1) throw new IllegalArgumentException("generations must be at least 1");
        if (horizon.isZero() || horizon.isNegative()) throw new IllegalArgumentException("horizon must be positive");
        this.horizon = horizon;
        this.generations = generations;
        this.rotateInterval = horizon.dividedBy(generations);
        this.expectedEntries = expectedEntries;
        this.entriesPerGeneration = Math.max(1, expectedEntries / generations);
        this.falsePositiveRate = falsePositiveRate;
        this.current = new Filters(newGeneration(), List.of(), null, false);
    }

    /**
     * Whether the filters may be trusted to answer "never seen".
     *
     * <p>False until the startup load has been published, because a partially
     * filled filter reports "never seen" for things it has seen. Until then the
     * database answers, which is exactly the behaviour that predates this class:
     * warm-up costs latency, not correctness.
     */
    public boolean isReady() {
        return current.ready();
    }

    /**
     * Returns {@code true} only when no generation has this identifier. Writer
     * thread only.
     */
    public boolean definitelyNew(UUID id) {
        Filters filters = current;
        if (!filters.live().definitelyNew(id)) return false;
        List<CommandDedupIndex> retained = filters.retained();
        for (int i = 0; i < retained.size(); i++) {
            if (!retained.get(i).definitelyNew(id)) return false;
        }
        CommandDedupIndex history = filters.history();
        return history == null || history.definitelyNew(id);
    }

    /** Records an identifier in the live generation. Writer thread only. */
    public void remember(UUID id) {
        current.live().remember(id);
    }

    /**
     * Hands over the filter the startup load filled, after which the index
     * answers instead of the database.
     *
     * <p>Must not be called until both the load and write-ahead log replay have
     * finished. Publishing early leaves holes, and a hole reads as "never seen".
     */
    public synchronized void publishHistory(CommandDedupIndex loaded) {
        Filters filters = current;
        this.rotationsSinceHistory = 0;
        this.current = new Filters(filters.live(), filters.retained(), loaded, true);
    }

    /**
     * Retires the live generation and starts a fresh one, dropping whichever
     * retained generation has aged past the horizon.
     *
     * <p>The startup history is released once the retained generations cover the
     * horizon on their own, which is exactly {@code generations} rotations after
     * it was published. Holding it longer would keep the largest single filter
     * alive for no added coverage.
     */
    public synchronized void rotate() {
        Filters filters = current;
        List<CommandDedupIndex> retained = new ArrayList<>(generations);
        retained.add(filters.live());
        List<CommandDedupIndex> previous = filters.retained();
        for (int i = 0; i < previous.size() && retained.size() < generations; i++) {
            retained.add(previous.get(i));
        }
        CommandDedupIndex history = filters.history();
        if (history != null) {
            rotationsSinceHistory++;
            if (rotationsSinceHistory >= generations) history = null;
        }
        this.current = new Filters(newGeneration(), List.copyOf(retained), history, filters.ready());
    }

    /** A filter sized for the whole horizon, for the startup load to fill. */
    public CommandDedupIndex newHistoryFilter() {
        return new CommandDedupIndex(expectedEntries, falsePositiveRate);
    }

    /** How far back an identifier is guaranteed to stay known. */
    public Duration horizon() {
        return horizon;
    }

    /** How often {@link #rotate()} should be called to deliver that horizon. */
    public Duration rotateInterval() {
        return rotateInterval;
    }

    /** Generations retained behind the live one, for reporting. */
    public int generations() {
        return generations;
    }

    /** Bytes currently held across every generation, for reporting. */
    public long bytes() {
        Filters filters = current;
        long bits = filters.live().bitCount();
        for (CommandDedupIndex generation : filters.retained()) {
            bits += generation.bitCount();
        }
        CommandDedupIndex history = filters.history();
        if (history != null) bits += history.bitCount();
        return bits / 8;
    }

    private CommandDedupIndex newGeneration() {
        return new CommandDedupIndex(entriesPerGeneration, falsePositiveRate);
    }
}

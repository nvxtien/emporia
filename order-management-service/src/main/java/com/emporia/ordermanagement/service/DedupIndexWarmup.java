package com.emporia.ordermanagement.service;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;


/**
 * Loads the horizon's identifiers during phased startup and hands the result to
 * {@link RotatingDedupIndex}, after which the hot path answers deduplication
 * and execution-reference questions from memory.
 *
 * <h2>Why the load runs before the OMS ring</h2>
 * <p>The BLP must not accept commands while its dedup index is incomplete.
 * Startup is deliberately blocked for this bounded durable read; a failure
 * leaves the index unpublished and commands fail closed.
 *
 * <h2>Ordering</h2>
 * <p>The lifecycle phase runs after live-order warmup and before
 * {@code DisruptorOrderPipeline}, so the durable history is published before
 * WAL replay and live intake.
 *
 * <h2>Concurrency</h2>
 * <p>The load fills its own filter rather than the live one. Two threads writing
 * one {@code long[]} would race on a read-modify-write, and a lost bit is a
 * false negative. The handoff is a single reference publication.
 *
 * <h2>Why this only runs once</h2>
 * <p>The filters are kept bounded by rotation rather than by reloading, so there
 * is no periodic version of this class. A live filter that has been rotated out
 * already <i>is</i> the history for the period it covered; re-reading it from
 * Postgres would buy nothing and cost a multi-million row scan on a schedule.
 * This load exists only to recover what happened before the process started.
 */
@Component
public class DedupIndexWarmup implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DedupIndexWarmup.class);

    private final @Nullable RotatingDedupIndex dedup;
    private final OrderStateCache cache;
    private final JdbcTemplate jdbcTemplate;
    private volatile boolean running;

    public DedupIndexWarmup(@Nullable RotatingDedupIndex dedup, JdbcTemplate jdbcTemplate, OrderStateCache cache) {
        this.dedup = dedup;
        this.jdbcTemplate = jdbcTemplate;
        this.cache = cache;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        warmUp();
        running = true;
    }

    public void warmUp() {
        if (dedup == null || jdbcTemplate == null) {
            log.info("Deduplication index disabled; hot-path commands remain unavailable");
            return;
        }
        loadAndPublish();
    }

    /**
     * Stops the lifecycle. A failed startup load is never published, so the
     * hot path remains fail-closed and retryable.
     */
    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /** Starts after live-order warmup and before the OMS ring. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 6144;
    }

    private void loadAndPublish() {
        try {
            CommandDedupIndex history = dedup.newHistoryFilter();
            long loaded = new DedupIndexLoader(jdbcTemplate).load(history, dedup.horizon(), key -> {
                cache.rememberExecutionReferenceKey(key);
                history.remember(key);
            });
            dedup.publishHistory(history);
            cache.markExecutionReferencesReady();
            log.info("Deduplication index ready: {} identifiers over {}, {} KB of filters. "
                            + "Hot-path lookups now answer from memory.",
                    loaded, dedup.horizon(), dedup.bytes() / 1024);
        } catch (RuntimeException loadFailure) {
            // Never fatal, and deliberately never published on failure: a
            // partially filled filter reports "never seen" for things it has
            // seen, which would let duplicate orders through. The BLP stays
            // fail-closed and returns retryable not-ready responses.
            log.error("Deduplication index load failed; hot-path commands stay unavailable", loadFailure);
        }
    }
}

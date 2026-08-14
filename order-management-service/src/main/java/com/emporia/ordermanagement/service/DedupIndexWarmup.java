package com.emporia.ordermanagement.service;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads the horizon's identifiers in the background and hands the result to
 * {@link RotatingDedupIndex}, after which the hot path stops asking Postgres
 * whether it has seen a command before.
 *
 * <h2>Why the load runs after startup rather than during it</h2>
 * <p>Blocking startup would be simpler, but the service would refuse orders for
 * the duration. Running afterwards means orders are accepted from the first
 * moment, answered by the database until the load finishes - which is exactly
 * the behaviour that predates the index, and its latency. Warm-up costs speed,
 * not correctness.
 *
 * <h2>Ordering</h2>
 * <p>{@link ApplicationReadyEvent} fires after {@code DisruptorOrderPipeline}'s
 * {@code @PostConstruct}, which has by then replayed the write-ahead log through
 * the handler - so commands accepted but unwritten before the last stop are
 * already in the live filter. The load adds the durable history. Publishing
 * happens only once both are in, because a filter missing entries reads as
 * "never seen", and that is how a duplicate order gets accepted.
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
public class DedupIndexWarmup {

    private static final Logger log = LoggerFactory.getLogger(DedupIndexWarmup.class);

    private final @Nullable RotatingDedupIndex dedup;
    private final JdbcTemplate jdbcTemplate;
    // Owned for the bean's life and shut down in @PreDestroy, rather than
    // created inside the listener where it would outlive its only reference.
    private final ExecutorService loader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dedup-index-warmup");
        thread.setDaemon(true);
        return thread;
    });

    public DedupIndexWarmup(@Nullable RotatingDedupIndex dedup, JdbcTemplate jdbcTemplate) {
        this.dedup = dedup;
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (dedup == null || jdbcTemplate == null) {
            log.info("Deduplication index disabled; hot-path lookups continue to read through to Postgres");
            return;
        }
        loader.execute(this::loadAndPublish);
    }

    /**
     * Stops a load still running when the service shuts down.
     *
     * <p>{@code shutdownNow} rather than {@code shutdown}: the load is a long
     * streaming read and waiting for it would hold up shutdown for no gain. An
     * abandoned load is never published, so stopping midway loses nothing but
     * the work already done - the hot path simply stays on Postgres.
     */
    @PreDestroy
    public void stop() {
        loader.shutdownNow();
    }

    private void loadAndPublish() {
        try {
            CommandDedupIndex history = dedup.newHistoryFilter();
            long loaded = new DedupIndexLoader(jdbcTemplate).load(history, dedup.horizon());
            dedup.publishHistory(history);
            log.info("Deduplication index ready: {} identifiers over {}, {} KB of filters. "
                            + "Hot-path lookups now answer from memory.",
                    loaded, dedup.horizon(), dedup.bytes() / 1024);
        } catch (RuntimeException loadFailure) {
            // Never fatal, and deliberately never published on failure: a
            // partially filled filter reports "never seen" for things it has
            // seen, which would let duplicate orders through. Staying on the
            // database path costs latency and nothing else.
            log.error("Deduplication index load failed; hot-path lookups stay on Postgres", loadFailure);
        }
    }
}

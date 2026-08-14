package com.emporia.ordermanagement.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads the session's processed commands in the background and hands the result
 * to {@link OrderStateCache}, after which the hot path stops asking Postgres
 * whether it has seen a command before.
 *
 * <h2>Why the load runs after startup rather than during it</h2>
 * <p>Blocking startup would be simpler, but the service would refuse orders for
 * the duration. Running afterwards means orders are accepted from the first
 * moment, answered by the database until the load finishes - which is exactly
 * today's behaviour and today's latency. Warm-up costs speed, not correctness.
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
 * false negative. The handoff is a single volatile write of the reference.
 */
@Component
public class DedupIndexWarmup {

    private static final Logger log = LoggerFactory.getLogger(DedupIndexWarmup.class);

    private final OrderStateCache cache;
    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;
    private final Duration window;
    private final long expectedEntries;
    private final double falsePositiveRate;
    // Owned for the bean's life and shut down in @PreDestroy, rather than
    // created inside the listener where it would outlive its only reference.
    private final ExecutorService loader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dedup-index-warmup");
        thread.setDaemon(true);
        return thread;
    });

    public DedupIndexWarmup(
            OrderStateCache cache,
            JdbcTemplate jdbcTemplate,
            @Value("${emporia.dedup-index.enabled:false}") boolean enabled,
            @Value("${emporia.dedup-index.session-window:PT8H}") Duration window,
            @Value("${emporia.dedup-index.expected-entries:3500000}") long expectedEntries,
            @Value("${emporia.dedup-index.false-positive-rate:0.001}") double falsePositiveRate) {
        this.cache = cache;
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
        this.window = window;
        this.expectedEntries = expectedEntries;
        this.falsePositiveRate = falsePositiveRate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!enabled || jdbcTemplate == null) {
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
            CommandDedupIndex history = new CommandDedupIndex(expectedEntries, falsePositiveRate);
            long loaded = new DedupIndexLoader(jdbcTemplate).load(history, window);
            cache.publishSessionHistory(history);
            log.info("Deduplication index ready: {} commands over {}, {} KB of filter. "
                            + "Hot-path lookups now answer from memory.",
                    loaded, window, history.bitCount() / 8 / 1024);
        } catch (RuntimeException loadFailure) {
            // Never fatal, and deliberately never published on failure: a
            // partially filled filter reports "never seen" for things it has
            // seen, which would let duplicate orders through. Staying on the
            // database path costs latency and nothing else.
            log.error("Deduplication index load failed; hot-path lookups stay on Postgres", loadFailure);
        }
    }
}

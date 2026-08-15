package com.emporia.ordermanagement.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Creates the deduplication filters.
 *
 * <p>Unconditional. There was an {@code emporia.dedup-index.enabled} property
 * here, kept while the index was new because deduplication failing means a
 * duplicate position and a property beats a redeploy. It is gone because the
 * evidence it was waiting for arrived: two hours at 10 orders/sec with one
 * request in ten replaying an earlier {@code Idempotency-Key} - roughly 7,160
 * real duplicates, none reaching the database - on top of the horizon and crash
 * behaviour being demonstrated rather than argued. See {@code CONFIGURATION.md}.
 *
 * <p>What replaces it is not nothing. The two duplicate counters are
 * database-side, so they still report a failure of this index from the far side
 * of it, and turning the index off is now a deployment rather than a property.
 */
@Configuration
public class DedupIndexConfig {

    /**
     * The filters the writer thread reads and fills.
     *
     * <p>{@code expected-entries} counts the whole horizon and both key spaces -
     * {@code commandId} for idempotency and {@code orderId} for the duplicate
     * guard - because they share the filters. Under-sizing is safe: it raises
     * the false-positive rate, and a false positive costs one Postgres lookup
     * that then gives the right answer.
     */
    @Bean
    public RotatingDedupIndex rotatingDedupIndex(
            @Value("${emporia.dedup-index.horizon:PT24H}") Duration horizon,
            @Value("${emporia.dedup-index.generations:4}") int generations,
            @Value("${emporia.dedup-index.expected-entries:20000000}") long expectedEntries,
            @Value("${emporia.dedup-index.false-positive-rate:0.001}") double falsePositiveRate) {
        return new RotatingDedupIndex(horizon, generations, expectedEntries, falsePositiveRate);
    }
}

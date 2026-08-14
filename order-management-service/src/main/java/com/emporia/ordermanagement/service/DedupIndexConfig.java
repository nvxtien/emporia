package com.emporia.ordermanagement.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Creates the deduplication filters when the index is switched on.
 *
 * <p>No bean means {@link OrderStateCache} receives null and reads through to
 * Postgres exactly as before, so the feature rolls out and rolls back by one
 * property rather than by a deployment.
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
    @ConditionalOnProperty(name = "emporia.dedup-index.enabled", havingValue = "true")
    public RotatingDedupIndex rotatingDedupIndex(
            @Value("${emporia.dedup-index.horizon:PT24H}") Duration horizon,
            @Value("${emporia.dedup-index.generations:4}") int generations,
            @Value("${emporia.dedup-index.expected-entries:20000000}") long expectedEntries,
            @Value("${emporia.dedup-index.false-positive-rate:0.001}") double falsePositiveRate) {
        return new RotatingDedupIndex(horizon, generations, expectedEntries, falsePositiveRate);
    }
}

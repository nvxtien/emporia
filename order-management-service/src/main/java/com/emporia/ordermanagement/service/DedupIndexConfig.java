package com.emporia.ordermanagement.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the live deduplication filter when the index is switched on.
 *
 * <p>No bean means {@link OrderStateCache} receives null and reads through to
 * Postgres exactly as before, so the feature rolls out and rolls back by one
 * property rather than by a deployment.
 */
@Configuration
public class DedupIndexConfig {

    /**
     * The filter the writer thread fills as it handles commands. Sized like the
     * session filter because between them they hold one session's identifiers,
     * and both key spaces - commandId and orderId - go into them.
     */
    @Bean
    @ConditionalOnProperty(name = "emporia.dedup-index.enabled", havingValue = "true")
    public CommandDedupIndex commandDedupIndex(
            @Value("${emporia.dedup-index.expected-entries:3500000}") long expectedEntries,
            @Value("${emporia.dedup-index.false-positive-rate:0.001}") double falsePositiveRate) {
        return new CommandDedupIndex(expectedEntries, falsePositiveRate);
    }
}

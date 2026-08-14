package com.emporia.ordermanagement.service;

import com.emporia.ordermanagement.repository.ProcessedCommandRepository;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderStateCacheIndexTest {

    private final TradingOrderRepository orders = mock(TradingOrderRepository.class);
    private final ProcessedCommandRepository processed = mock(ProcessedCommandRepository.class);
    private final OrderMetrics metrics = new OrderMetrics(new SimpleMeterRegistry());

    private static RotatingDedupIndex dedupIndex() {
        return new RotatingDedupIndex(Duration.ofHours(24), 4, 1_000, 0.001);
    }

    private OrderStateCache cacheWith(RotatingDedupIndex dedup) {
        return new OrderStateCache(orders, processed, metrics, dedup, 1000, 1000);
    }

    /**
     * The invariant the warm-up rests on. A filter that has not finished loading
     * reports "never seen" for things it has seen, so trusting it early is how a
     * duplicate order gets accepted.
     */
    @Test
    void readsThroughToPostgresUntilTheHistoryIsPublished() {
        OrderStateCache cache = cacheWith(dedupIndex());
        UUID id = UUID.randomUUID();
        when(orders.existsById(id)).thenReturn(false);

        assertThat(cache.isReady()).isFalse();
        cache.existsById(id);

        verify(orders).existsById(id);
    }

    @Test
    void answersFromMemoryOnceTheHistoryIsPublished() {
        RotatingDedupIndex dedup = dedupIndex();
        OrderStateCache cache = cacheWith(dedup);
        dedup.publishHistory(new CommandDedupIndex(1_000, 0.001));

        assertThat(cache.isReady()).isTrue();
        assertThat(cache.existsById(UUID.randomUUID())).isFalse();
        assertThat(cache.findProcessedById(UUID.randomUUID())).isEmpty();

        verify(orders, never()).existsById(any());
        verify(processed, never()).findById(any());
    }

    /**
     * An identifier the loader recovered must not read as new, even though the
     * live filter - the one the writer thread fills - has never seen it.
     */
    @Test
    void anIdentifierKnownOnlyToTheLoadedHistoryStillReachesPostgres() {
        UUID recovered = UUID.randomUUID();
        CommandDedupIndex history = new CommandDedupIndex(1_000, 0.001);
        history.remember(recovered);

        RotatingDedupIndex dedup = dedupIndex();
        OrderStateCache cache = cacheWith(dedup);
        dedup.publishHistory(history);
        when(processed.findById(recovered)).thenReturn(Optional.empty());

        cache.findProcessedById(recovered);

        verify(processed).findById(recovered);
    }

    /**
     * An identifier this process handled itself must survive rotation for the
     * whole horizon. Losing it early is a false negative, and a false negative on
     * an orderId is a second order carrying an id that already exists.
     */
    @Test
    void anIdentifierThisProcessHandledSurvivesRotationAcrossTheHorizon() {
        RotatingDedupIndex dedup = dedupIndex();
        OrderStateCache cache = cacheWith(dedup);
        dedup.publishHistory(new CommandDedupIndex(1_000, 0.001));

        UUID commandId = UUID.randomUUID();
        dedup.remember(commandId);
        for (int rotation = 0; rotation < 4; rotation++) {
            dedup.rotate();
        }
        when(processed.findById(commandId)).thenReturn(Optional.empty());

        cache.findProcessedById(commandId);

        verify(processed).findById(commandId);
    }

    @Test
    void aNullIndexKeepsTheOriginalReadThroughBehaviour() {
        OrderStateCache cache = cacheWith(null);
        UUID id = UUID.randomUUID();
        when(orders.existsById(id)).thenReturn(false);

        assertThat(cache.isReady()).isFalse();
        cache.existsById(id);

        verify(orders).existsById(id);
    }
}

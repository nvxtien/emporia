package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.ProcessedCommandRepository;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiveOrderStoreWarmupTest {

    private final TradingOrderRepository orders = mock(TradingOrderRepository.class);
    private final ProcessedCommandRepository processed = mock(ProcessedCommandRepository.class);
    private final OrderMetrics metrics = new OrderMetrics(new SimpleMeterRegistry());
    private final OrderStateCache cache =
            new OrderStateCache(orders, processed, metrics, null, 1000, 1000);

    @Test
    void loadsEveryLiveOrderAcrossPagesAndThenDeclaresTheSetComplete() {
        List<TradingOrder> first = List.of(liveOrder(), liveOrder());
        List<TradingOrder> second = List.of(liveOrder());
        Pageable page0 = PageRequest.of(0, 2);
        when(orders.findByStatusIn(anyCollection(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(first, page0, 3))
                .thenReturn(new PageImpl<>(second, page0.next(), 3));

        new LiveOrderStoreWarmup(cache, orders, 2).load();

        assertThat(cache.liveOrderCount()).isEqualTo(3);
        assertThat(cache.isLiveSetComplete()).isTrue();
    }

    /**
     * The discipline the whole design rests on. A partially loaded store reports
     * "not live" for orders that are, so anything trusting it would act on that
     * - which is why failure leaves the flag false rather than marking a set it
     * could not finish.
     */
    @Test
    void aFailedLoadLeavesTheSetIncompleteRatherThanMarkingItComplete() {
        when(orders.findByStatusIn(anyCollection(), any(Pageable.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        new LiveOrderStoreWarmup(cache, orders, 2).load();

        assertThat(cache.isLiveSetComplete())
                .as("an unfinished load must never be reported as a complete set")
                .isFalse();
    }

    @Test
    void aPartialLoadThatFailsMidwayIsStillNotMarkedComplete() {
        Page<TradingOrder> firstPage = new PageImpl<>(List.of(liveOrder()), PageRequest.of(0, 1), 2);
        when(orders.findByStatusIn(anyCollection(), any(Pageable.class)))
                .thenReturn(firstPage)
                .thenThrow(new IllegalStateException("connection lost mid-load"));

        new LiveOrderStoreWarmup(cache, orders, 1).load();

        assertThat(cache.liveOrderCount()).isEqualTo(1);
        assertThat(cache.isLiveSetComplete()).isFalse();
    }

    private static TradingOrder liveOrder() {
        UUID orderId = UUID.randomUUID();
        return new TradingOrder(
                orderId, "warmup-test-user", "desk-a", listing(), OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100.00"), "DMA", "warmup-test",
                null, orderId, "{}");
    }

    private static ListingSnapshot listing() {
        return new ListingSnapshot(
                1, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq", "US", "USD",
                new BigDecimal("0.01"), new BigDecimal("0.01"),
                new BigDecimal("200.00"), new BigDecimal("198.00"));
    }
}

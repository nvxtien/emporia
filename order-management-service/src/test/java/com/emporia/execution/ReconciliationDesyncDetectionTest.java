package com.emporia.execution;

import com.emporia.events.TradingEvents.ExecutionRecoveryView;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * The endpoint's own job is comparing two sets, so these tests speak the
 * venue-agnostic seam and compile into both artifacts. Translating an
 * order-management id into a venue's own identifier scheme is the gateway's
 * job and is tested with the gateway.
 */
class ReconciliationDesyncDetectionTest {

    @Test
    void detectsDeliberatelyDesyncedMissingOrdersOnVenue() {
        OrderView liveOrder = createOrder(OrderStatus.LIVE);
        ExecutionVenueGateway gateway = gatewayReturning(VenueOpenOrders.of(Set.of(), List.of()));

        ReconciliationEndpoint.Report report =
                new ReconciliationEndpoint(tradingDataWith(liveOrder), gateway).reconcile();

        assertTrue(report.supported());
        assertEquals(1, report.ordersChecked());
        assertEquals(1, report.missingCount());
        assertEquals(liveOrder.id() + " (LIVE)", report.missingOrders().get(0));
    }

    @Test
    void detectsGhostOrdersOnVenueThatDoNotExistInOms() {
        OrderView liveOrder = createOrder(OrderStatus.LIVE);
        ExecutionVenueGateway gateway = gatewayReturning(
                VenueOpenOrders.of(Set.of(liveOrder.id()), List.of("core-order-999888777 (client 42)")));

        ReconciliationEndpoint.Report report =
                new ReconciliationEndpoint(tradingDataWith(liveOrder), gateway).reconcile();

        assertEquals(0, report.missingCount());
        assertEquals(1, report.ghostCount(), "Reverse reconciliation must catch ghost orders on venue!");
        assertEquals("core-order-999888777 (client 42)", report.ghostOrders().get(0));
    }

    @Test
    void reportsZeroMissingOrdersWhenVenueAndOmsMatch() {
        OrderView liveOrder = createOrder(OrderStatus.LIVE);
        ExecutionVenueGateway gateway = gatewayReturning(VenueOpenOrders.of(Set.of(liveOrder.id()), List.of()));

        ReconciliationEndpoint.Report report =
                new ReconciliationEndpoint(tradingDataWith(liveOrder), gateway).reconcile();

        assertEquals(1, report.ordersChecked());
        assertEquals(0, report.missingCount());
        assertEquals(0, report.ghostCount());
        assertTrue(report.missingOrders().isEmpty());
    }

    /**
     * The failure this guards is the loudest one available: a venue that cannot
     * answer must not have its silence read as "every live order is missing".
     * That is why {@code supported} is a field rather than an empty answer.
     */
    @Test
    void aVenueThatCannotAnswerIsNotReportedAsHavingLostEveryOrder() {
        OrderView liveOrder = createOrder(OrderStatus.LIVE);
        ExecutionVenueGateway gateway = gatewayReturning(VenueOpenOrders.unsupported());

        ReconciliationEndpoint.Report report =
                new ReconciliationEndpoint(tradingDataWith(liveOrder), gateway).reconcile();

        assertFalse(report.supported(), "an unanswerable report must say so");
        assertEquals(1, report.ordersChecked());
        assertEquals(0, report.missingCount(), "silence is not evidence of loss");
        assertEquals(0, report.ghostCount());
    }

    private static ExecutionVenueGateway gatewayReturning(VenueOpenOrders answer) {
        ExecutionVenueGateway gateway = mock(ExecutionVenueGateway.class);
        when(gateway.venueMode()).thenReturn("test-venue");
        when(gateway.openOrders(anyList())).thenReturn(CompletableFuture.completedFuture(answer));
        return gateway;
    }

    private static TradingDataClient tradingDataWith(OrderView... orders) {
        TradingDataClient tradingDataClient = mock(TradingDataClient.class);
        when(tradingDataClient.recoverable())
                .thenReturn(new ExecutionRecoveryView(List.of(orders), List.of()));
        return tradingDataClient;
    }

    private static OrderView createOrder(OrderStatus status) {
        return new OrderView(
                UUID.randomUUID(), 2, "trader-alpha", "desk-a", listing(),
                OrderSide.BUY, OrderType.LIMIT, new BigDecimal("10"),
                new BigDecimal("102.25"), new BigDecimal("10"), BigDecimal.ZERO, null,
                status, status, "DMA", "desynced-order-test",
                null, UUID.randomUUID(), "{}", null,
                Instant.parse("2026-07-26T00:00:00Z"),
                Instant.parse("2026-07-26T00:00:01Z"));
    }

    private static ListingSnapshot listing() {
        return new ListingSnapshot(
                7, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq",
                "US", "USD", new BigDecimal("0.01"), BigDecimal.ONE,
                new BigDecimal("101"), new BigDecimal("100"));
    }
}

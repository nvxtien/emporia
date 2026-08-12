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
import static org.mockito.Mockito.*;

class ReconciliationDesyncDetectionTest {

    @Test
    void detectsDeliberatelyDesyncedMissingOrdersOnVenue() {
        // Step 1: Create a live order view in OMS
        OrderView liveOrder = createOrder(OrderStatus.LIVE);

        TradingDataClient tradingDataClient = mock(TradingDataClient.class);
        ExecutionRecoveryView recoveryView = new ExecutionRecoveryView(List.of(liveOrder), List.of());
        when(tradingDataClient.recoverable()).thenReturn(recoveryView);

        ExchangeCoreExecutionVenueGateway venueGateway = mock(ExchangeCoreExecutionVenueGateway.class);
        long clientId = ExchangeCoreExecutionVenueGateway.clientId(liveOrder);

        // Step 2: Simulate venue state where venue does NOT have the order (empty set)
        when(venueGateway.openOrderIds(clientId)).thenReturn(CompletableFuture.completedFuture(Set.of()));

        ReconciliationEndpoint endpoint = new ReconciliationEndpoint(tradingDataClient, venueGateway);

        // Step 3: Run reconciliation audit
        ReconciliationEndpoint.Report report = endpoint.reconcile();

        // Step 4: Verify that reconciliation endpoint detects the deliberate mismatch
        assertEquals(1, report.ordersChecked());
        assertEquals(1, report.missingCount());
        assertFalse(report.missingOrders().isEmpty(), "Reconciliation must detect missing desynced order!");
        assertEquals(liveOrder.id() + " (LIVE)", report.missingOrders().get(0));
    }

    @Test
    void detectsGhostOrdersOnVenueThatDoNotExistInOms() {
        OrderView liveOrder = createOrder(OrderStatus.LIVE);

        TradingDataClient tradingDataClient = mock(TradingDataClient.class);
        ExecutionRecoveryView recoveryView = new ExecutionRecoveryView(List.of(liveOrder), List.of());
        when(tradingDataClient.recoverable()).thenReturn(recoveryView);

        ExchangeCoreExecutionVenueGateway venueGateway = mock(ExchangeCoreExecutionVenueGateway.class);
        long clientId = ExchangeCoreExecutionVenueGateway.clientId(liveOrder);
        long expectedCoreId = ExchangeCoreExecutionVenueGateway.coreOrderId(liveOrder);
        long ghostCoreId = 999888777L;

        // Venue has expected order AND an extra ghost order
        when(venueGateway.openOrderIds(clientId)).thenReturn(CompletableFuture.completedFuture(Set.of(expectedCoreId, ghostCoreId)));

        ReconciliationEndpoint endpoint = new ReconciliationEndpoint(tradingDataClient, venueGateway);

        ReconciliationEndpoint.Report report = endpoint.reconcile();

        assertEquals(1, report.ordersChecked());
        assertEquals(0, report.missingCount());
        assertEquals(1, report.ghostCount(), "Reverse reconciliation must catch ghost orders on venue!");
        assertEquals("ghost-core-order-" + ghostCoreId, report.ghostOrders().get(0));
    }

    @Test
    void reportsZeroMissingOrdersWhenVenueAndOmsMatch() {
        OrderView liveOrder = createOrder(OrderStatus.LIVE);

        TradingDataClient tradingDataClient = mock(TradingDataClient.class);
        ExecutionRecoveryView recoveryView = new ExecutionRecoveryView(List.of(liveOrder), List.of());
        when(tradingDataClient.recoverable()).thenReturn(recoveryView);

        ExchangeCoreExecutionVenueGateway venueGateway = mock(ExchangeCoreExecutionVenueGateway.class);
        long clientId = ExchangeCoreExecutionVenueGateway.clientId(liveOrder);
        long coreOrderId = ExchangeCoreExecutionVenueGateway.coreOrderId(liveOrder);

        // Simulate venue state where venue HAS the expected order ID
        when(venueGateway.openOrderIds(clientId)).thenReturn(CompletableFuture.completedFuture(Set.of(coreOrderId)));

        ReconciliationEndpoint endpoint = new ReconciliationEndpoint(tradingDataClient, venueGateway);

        ReconciliationEndpoint.Report report = endpoint.reconcile();

        assertEquals(1, report.ordersChecked());
        assertEquals(0, report.missingCount());
        assertEquals(0, report.ghostCount());
        assertTrue(report.missingOrders().isEmpty());
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

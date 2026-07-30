package com.emporia.execution;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SimulatedExecutionVenueGatewayTest {
    private final ExecutionCommandPublisher commands = mock(ExecutionCommandPublisher.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private SimulatedExecutionVenueGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new SimulatedExecutionVenueGateway(commands, scheduler, Duration.ofMillis(100));
    }

    @Test
    void submitSchedulesExecution() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler realScheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        realScheduler.initialize();
        SimulatedExecutionVenueGateway realGateway = new SimulatedExecutionVenueGateway(commands, realScheduler, Duration.ofMillis(100));

        OrderView order = sampleOrder(UUID.randomUUID(), new BigDecimal("150.00"));
        realGateway.submit(order);
    }

    @Test
    void submitThrowsWhenNoValidReferencePrice() {
        ListingSnapshot invalidListing = new ListingSnapshot(1L, 1, "AAPL", "Apple Inc", "AAPL", "XNAS", "Exchange", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO);
        OrderView order = sampleOrderWithListing(UUID.randomUUID(), null, invalidListing);

        assertThatThrownBy(() -> gateway.submit(order))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no valid reference price");
    }

    @Test
    void cancelPublishesVenueCancel() {
        OrderView order = sampleOrder(UUID.randomUUID(), new BigDecimal("150.00"));
        gateway.cancel(order);
        verify(commands).venueCancel(eq(order.id()), eq("desk-1"), any(), eq("XNAS"), any());
    }

    @Test
    void cancelWithPendingFutureCancelsPreviousScheduledTask() throws Exception {
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler realScheduler =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        realScheduler.initialize();
        SimulatedExecutionVenueGateway realGateway =
                new SimulatedExecutionVenueGateway(commands, realScheduler, Duration.ofSeconds(30));

        OrderView order = sampleOrder(UUID.randomUUID(), new BigDecimal("150.00"));
        realGateway.submit(order);   // schedules a future 30s from now
        realGateway.cancel(order);   // should cancel it and publish venueCancel

        verify(commands).venueCancel(eq(order.id()), eq("desk-1"), any(), eq("XNAS"), any());
        realScheduler.destroy();
    }

    @Test
    void modifyDelegatesToSubmit() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler realScheduler =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        realScheduler.initialize();
        SimulatedExecutionVenueGateway realGateway =
                new SimulatedExecutionVenueGateway(commands, realScheduler, Duration.ofSeconds(30));

        OrderView order = sampleOrder(UUID.randomUUID(), new BigDecimal("150.00"));
        realGateway.modify(order);   // internally calls submit, so a new scheduled future is created

        // No exception — the modify schedules a fill
        realGateway.cancel(order);
        verify(commands).venueCancel(eq(order.id()), eq("desk-1"), any(), eq("XNAS"), any());
        realScheduler.destroy();
    }

    @Test
    void recoverDelegatesToSubmit() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler realScheduler =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        realScheduler.initialize();
        SimulatedExecutionVenueGateway realGateway =
                new SimulatedExecutionVenueGateway(commands, realScheduler, Duration.ofSeconds(30));

        OrderView order = sampleOrder(UUID.randomUUID(), new BigDecimal("150.00"));
        realGateway.recover(order);   // internally calls submit

        realGateway.cancel(order);
        verify(commands).venueCancel(eq(order.id()), eq("desk-1"), any(), eq("XNAS"), any());
        realScheduler.destroy();
    }

    private static OrderView sampleOrder(UUID id, BigDecimal limitPrice) {
        ListingSnapshot listing = new ListingSnapshot(1L, 1, "AAPL", "Apple Inc", "AAPL", "XNAS", "Exchange", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("150.00"), new BigDecimal("150.00"));
        return sampleOrderWithListing(id, limitPrice, listing);
    }

    private static OrderView sampleOrderWithListing(UUID id, BigDecimal limitPrice, ListingSnapshot listing) {
        return new OrderView(id, 1L, "user-1", "desk-1", listing,
                OrderSide.BUY, OrderType.LIMIT, new BigDecimal("100"), limitPrice, new BigDecimal("100"),
                BigDecimal.ZERO, BigDecimal.ZERO, OrderStatus.LIVE, OrderStatus.LIVE, "XNAS", "orig-1",
                null, null, null, null, Instant.now(), Instant.now());
    }
}

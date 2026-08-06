package com.emporia.execution;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionBoundaryContractTest {
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final TradingDataClient tradingData = mock(TradingDataClient.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final ExecutionVenueGateway venue = mock(ExecutionVenueGateway.class);
    private final ExecutionCommandPublisher executionCommands = mock(ExecutionCommandPublisher.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ExecutionEventConsumer consumer = new ExecutionEventConsumer(
            objectMapper,
            kafka,
            tradingData,
            scheduler,
            venue,
            executionCommands,
            new SimpleMeterRegistry(),
            ObservationRegistry.create(),
            "orders.commands",
            60,
            5,
            "simulated",
            3
    );

    @Test
    void createdDmaRoutesOnlyToVenueSubmit() throws Exception {
        OrderView order = order("DMA", null, OrderStatus.LIVE);

        consumer.consume(event("CREATED", order));

        verify(venue).submit(order);
        verify(venue, never()).modify(any());
        verify(venue, never()).cancel(any());
        verify(kafka, never()).send(eq("orders.commands"), any(), any());
        verify(executionCommands, never()).venueCancel(any(), any(), any(), any(), any());
    }

    @Test
    void cancelRequestedForDmaUsesVenueCancelPath() throws Exception {
        OrderView order = order("DMA", null, OrderStatus.LIVE);

        consumer.consume(event("CANCEL_REQUESTED", order));

        verify(venue).cancel(order);
        verify(executionCommands, never()).venueCancel(any(), any(), any(), any(), any());
    }

    @Test
    void cancelRequestedForStrategyUsesExecutionCommandPath() throws Exception {
        OrderView order = order("SMART", null, OrderStatus.LIVE);

        consumer.consume(event("CANCEL_REQUESTED", order));

        verify(executionCommands).venueCancel(eq(order.id()), eq(order.deskId()), any(), any(), any());
        verify(venue, never()).cancel(any());
    }

    @Test
    void unknownNonTerminalParentEventIsIgnored() throws Exception {
        OrderView order = order("SMART", null, OrderStatus.LIVE);

        consumer.consume(event("ACKNOWLEDGED", order));

        verify(venue, never()).submit(any());
        verify(venue, never()).modify(any());
        verify(venue, never()).cancel(any());
        verify(kafka, never()).send(any(), any(), any());
        verify(executionCommands, never()).fill(any(), any(), any(), any(), any(), any(), any());
        verify(executionCommands, never()).reject(any(), any(), any(), any(), any());
        verify(executionCommands, never()).venueCancel(any(), any(), any(), any(), any());
    }

    @Test
    void modifiedNonDmaDoesNotCallVenueModify() throws Exception {
        OrderView order = order("VWAP", null, OrderStatus.LIVE);

        consumer.consume(event("MODIFIED", order));

        verify(venue, never()).modify(any());
        verify(executionCommands, never()).venueCancel(any(), any(), any(), any(), any());
    }

    @Test
    void createdSmartWithNoLiquidityAvoidsVenueDirectCalls() throws Exception {
        OrderView order = order("SMART", null, OrderStatus.LIVE);
        when(tradingData.sameInstrument(order.listing().id())).thenReturn(List.of(order.listing()));
        when(tradingData.quotes(List.of(order.listing().id()))).thenReturn(List.of());

        consumer.consume(event("CREATED", order));

        verify(venue, never()).submit(any());
        verify(venue, never()).modify(any());
        verify(venue, never()).cancel(any());
    }

    private OrderDomainEvent event(String type, OrderView order) throws Exception {
        return new OrderDomainEvent(
                SCHEMA_VERSION,
                UUID.randomUUID(),
                UUID.randomUUID(),
                order.id(),
                order.ownerSubject(),
                order.deskId(),
                type,
                order.version(),
                order.status(),
                Instant.parse("2026-07-26T00:00:00Z"),
                objectMapper.writeValueAsString(order)
        );
    }

    private static OrderView order(String destination, UUID parentId, OrderStatus status) {
        BigDecimal quantity = new BigDecimal("10");
        BigDecimal traded = status == OrderStatus.FILLED ? quantity : BigDecimal.ZERO;
        BigDecimal remaining = quantity.subtract(traded);
        return new OrderView(
                UUID.randomUUID(),
                2,
                "trader-a",
                "desk-a",
                listing(7, "XNAS", "Nasdaq"),
                OrderSide.BUY,
                OrderType.LIMIT,
                quantity,
                new BigDecimal("102"),
                remaining,
                traded,
                status == OrderStatus.FILLED ? new BigDecimal("101.25") : null,
                status,
                status,
                destination,
                "test-order",
                parentId,
                parentId == null ? UUID.randomUUID() : parentId,
                "{}",
                null,
                Instant.parse("2026-07-26T00:00:00Z"),
                Instant.parse("2026-07-26T00:00:01Z")
        );
    }

    private static ListingSnapshot listing(long id, String mic, String exchange) {
        return new ListingSnapshot(
                id,
                1,
                "AAPL",
                "Apple Inc.",
                "AAPL",
                mic,
                exchange,
                "US",
                "USD",
                new BigDecimal("0.01"),
                BigDecimal.ONE,
                new BigDecimal("101"),
                new BigDecimal("100")
        );
    }
}

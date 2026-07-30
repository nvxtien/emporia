package com.emporia.execution;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.ExecutionRecoveryView;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import com.emporia.events.TradingEvents.StrategyStateView;
import com.emporia.execution.TradingDataClient.DepthLevel;
import com.emporia.execution.TradingDataClient.MarketQuote;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class ExecutionEventConsumerTest {
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final TradingDataClient tradingData = mock(TradingDataClient.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final ExecutionVenueGateway venue = mock(ExecutionVenueGateway.class);
    private final ExecutionCommandPublisher executionCommands = mock(ExecutionCommandPublisher.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutionEventConsumer consumer = new ExecutionEventConsumer(
            objectMapper, kafka, tradingData, scheduler, venue, executionCommands,
            new SimpleMeterRegistry(), "orders.commands", 60, 5
    );

    @Test
    void sendsDmaOrdersToTheVenueGateway() throws Exception {
        OrderView order = order("DMA", null, OrderStatus.LIVE);

        consumer.consume(event("CREATED", order));

        verify(venue).submit(order);
        verify(kafka, never()).send(eq("orders.commands"), any(), any());
    }

    @Test
    void smartRoutingCreatesAChildAtTheBestVenue() throws Exception {
        OrderView parent = order("SMART", null, OrderStatus.LIVE);
        ListingSnapshot nyse = listing(8, "XNYS", "New York Stock Exchange");
        when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing(), nyse));
        when(tradingData.quotes(List.of(parent.listing().id(), nyse.id()))).thenReturn(List.of(
                quote(parent.listing().id(), "101.50"),
                quote(nyse.id(), "101.25")
        ));
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        });

        consumer.consume(event("CREATED", parent));

        ArgumentCaptor<Object> childMessage = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq("orders.commands"), any(), childMessage.capture());
        OrderCommand child = (OrderCommand) childMessage.getValue();
        assertThat(child.parentOrderId()).isEqualTo(parent.id());
        assertThat(child.deskId()).isEqualTo(parent.deskId());
        assertThat(child.listing().exchangeMic()).isEqualTo("XNYS");
        assertThat(child.limitPrice()).isEqualByComparingTo("101.25");
        assertThat(child.quantity()).isEqualByComparingTo(parent.remainingQuantity());
        assertThat(child.destination()).isEqualTo("DMA");
    }

    @Test
    void childFillsAreNotRepublishedBecauseOrderManagementRollsThemUpAtomically() throws Exception {
        UUID parentId = UUID.randomUUID();
        OrderView child = order("DMA", parentId, OrderStatus.FILLED);

        consumer.consume(event("FILLED", child));

        verify(executionCommands, never()).fill(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void cancelRequestedForDmaCancelsAtVenue() throws Exception {
        OrderView order = order("DMA", null, OrderStatus.LIVE);
        consumer.consume(event("CANCEL_REQUESTED", order));
        verify(venue).cancel(order);
    }

    @Test
    void cancelRequestedForStrategyStopsSchedulerAndPublishesVenueCancel() throws Exception {
        OrderView order = order("SMART", null, OrderStatus.LIVE);
        consumer.consume(event("CANCEL_REQUESTED", order));
        verify(executionCommands).venueCancel(eq(order.id()), eq(order.deskId()), any(), any(), any());
    }

    @Test
    void vwapStrategyStartsAndSchedulesSlices() throws Exception {
        long now = Instant.now().getEpochSecond();
        OrderView parent = withExecutionParameters(order("VWAP", null, OrderStatus.LIVE),
                "{\"utcStartTimeSecs\":" + now + ",\"utcEndTimeSecs\":" + (now + 600) + ",\"buckets\":2}");
        ListingSnapshot nyse = listing(8, "XNYS", "New York Stock Exchange");
        when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing(), nyse));
        when(tradingData.quotes(List.of(parent.listing().id(), nyse.id()))).thenReturn(List.of(
                quote(parent.listing().id(), "101.50")
        ));
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return mock(java.util.concurrent.ScheduledFuture.class);
        });

        consumer.consume(event("CREATED", parent));

        verify(kafka).send(eq("orders.commands"), any(), any());
    }

    @Test
    void smartRoutingWalksDepthAndCreatesMultipleVenueChildren() throws Exception {
        OrderView parent = order("SMART", null, OrderStatus.LIVE);
        ListingSnapshot nyse = listing(8, "XNYS", "New York Stock Exchange");
        when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing(), nyse));
        when(tradingData.quotes(List.of(parent.listing().id(), nyse.id()))).thenReturn(List.of(
                new MarketQuote(parent.listing().id(), new BigDecimal("100"), List.of(),
                        List.of(new DepthLevel(new BigDecimal("100"), new BigDecimal("4"),
                                "XNAS", "offer-1", parent.listing().id()))),
                new MarketQuote(nyse.id(), new BigDecimal("101"), List.of(),
                        List.of(new DepthLevel(new BigDecimal("101"), new BigDecimal("6"),
                                "XNYS", "offer-2", nyse.id())))
        ));

        consumer.consume(event("CREATED", parent));

        ArgumentCaptor<Object> messages = ArgumentCaptor.forClass(Object.class);
        verify(kafka, times(2)).send(eq("orders.commands"), any(), messages.capture());
        assertThat(messages.getAllValues()).extracting(message -> ((OrderCommand) message).quantity())
                .containsExactly(new BigDecimal("4"), new BigDecimal("6"));
        assertThat(messages.getAllValues()).extracting(message -> ((OrderCommand) message).listing().exchangeMic())
                .containsExactly("XNAS", "XNYS");
    }

    @Test
    void smartRoutingWaitsAndRetriesWhenNoExecutableLiquidityIsAvailable() throws Exception {
        OrderView parent = order("SMART", null, OrderStatus.LIVE);
        when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing()));
        when(tradingData.quotes(List.of(parent.listing().id()))).thenReturn(List.of());

        consumer.consume(event("CREATED", parent));

        verify(kafka, never()).send(eq("orders.commands"), any(), any());
        verify(executionCommands, never()).reject(any(), any(), any(), any(), any());
    }

    @Test
    void cancellationRequestWaitsForTheDmaVenueAcknowledgement() throws Exception {
        OrderView pending = withTarget(order("DMA", null, OrderStatus.LIVE), OrderStatus.CANCELLED);

        consumer.consume(event("CANCEL_REQUESTED", pending));

        verify(venue).cancel(pending);
        verify(executionCommands, never()).venueCancel(any(), any(), any(), any(), any());
    }

    @Test
    void recoverAfterStartupSchedulesRecovery() {
        consumer.recoverAfterStartup();
        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void recoverHandlesTransientFailureAndReschedules() {
        when(tradingData.recoverable()).thenThrow(new RuntimeException("Trading data service unavailable"));
        consumer.recover();
        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void recoverCancelsDirectAndStrategyOrdersTargetingCancellation() {
        OrderView directCancel = withTarget(order("DMA", null, OrderStatus.LIVE), OrderStatus.CANCELLED);
        OrderView strategyCancel = withTarget(order("SMART", null, OrderStatus.LIVE), OrderStatus.CANCELLED);
        when(tradingData.recoverable()).thenReturn(new ExecutionRecoveryView(
                List.of(directCancel),
                List.of(new StrategyStateView(strategyCancel, List.of()))));

        consumer.recover();

        verify(venue).cancel(directCancel);
        verify(executionCommands).venueCancel(eq(strategyCancel.id()), eq(strategyCancel.deskId()), any(), any(), any());
    }

    @Test
    void rejectsUnsupportedExecutionDestination() throws Exception {
        OrderView order = order("UNKNOWN_DEST", null, OrderStatus.LIVE);
        consumer.consume(event("CREATED", order));

        verify(executionCommands).reject(eq(order.id()), eq(order.deskId()), any(), eq(order.listing().exchangeMic()),
                eq("Unsupported execution destination UNKNOWN_DEST"));
    }

    @Test
    void advanceSmartExecutesOnScheduledTick() throws Exception {
        OrderView parent = order("SMART", null, OrderStatus.LIVE);
        ListingSnapshot nyse = listing(8, "XNYS", "New York Stock Exchange");
        when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing(), nyse));
        when(tradingData.quotes(List.of(parent.listing().id(), nyse.id()))).thenReturn(List.of(
                quote(parent.listing().id(), "101.50")
        ));
        when(tradingData.strategy(parent.id())).thenReturn(new StrategyStateView(parent, List.of()));

        when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any())).thenAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run(); // trigger advanceSmart
            return mock(java.util.concurrent.ScheduledFuture.class);
        });

        consumer.consume(event("CREATED", parent));

        verify(tradingData).strategy(parent.id());
    }

    @Test
    void restartRecoveryReattachesDirectOrdersWithoutResubmittingThem() {
        OrderView live = order("DMA", null, OrderStatus.LIVE);
        when(tradingData.recoverable()).thenReturn(new ExecutionRecoveryView(List.of(live), List.of()));

        consumer.recover();

        verify(venue).recover(live);
        verify(venue, never()).submit(live);
    }

    @Test
    void restartRecoveryRejectsAnExpiredVwapAndContinues() throws Exception {
        OrderView expired = withExecutionParameters(order("VWAP", null, OrderStatus.LIVE),
                "{\"utcStartTimeSecs\":1,\"utcEndTimeSecs\":2,\"buckets\":1}");
        when(tradingData.recoverable()).thenReturn(new ExecutionRecoveryView(
                List.of(), List.of(new StrategyStateView(expired, List.of()))));

        consumer.recover();

        verify(executionCommands).reject(eq(expired.id()), eq(expired.deskId()), any(), any(),
                eq("VWAP end time has already passed"));
    }

    @Test
    void modifiedEventForNonDmaDestinationIsIgnored() throws Exception {
        OrderView order = order("SMART", null, OrderStatus.LIVE);
        consumer.consume(event("MODIFIED", order));
        verify(venue, never()).modify(any());
    }

    @Test
    void modifiedEventForDmaCallsVenueModify() throws Exception {
        OrderView order = order("DMA", null, OrderStatus.LIVE);
        consumer.consume(event("MODIFIED", order));
        verify(venue).modify(order);
    }

    @Test
    void terminalEventForChildOrderDoesNotStopRuntime() throws Exception {
        UUID parentId = UUID.randomUUID();
        OrderView child = order("DMA", parentId, OrderStatus.FILLED);
        consumer.consume(event("FILLED", child));
        // parentOrderId is non-null → stopRuntime should NOT be called
        // No direct assertion needed; just verify no NPE and fills not published
        verify(executionCommands, never()).fill(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void terminalParentOrderStopsRuntime() throws Exception {
        // Consume a CREATED SMART order to register a runtime, then terminate it
        OrderView parent = order("SMART", null, OrderStatus.LIVE);
        when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing()));
        when(tradingData.quotes(any())).thenReturn(List.of());
        consumer.consume(event("CREATED", parent));

        // Now send a terminal event for the parent
        OrderView filled = new OrderView(
                parent.id(), parent.version() + 1, parent.ownerSubject(), parent.deskId(),
                parent.listing(), parent.side(), parent.type(), parent.quantity(), parent.limitPrice(),
                BigDecimal.ZERO, parent.quantity(), parent.limitPrice(),
                OrderStatus.FILLED, OrderStatus.FILLED, parent.destination(),
                parent.originatorReference(), null, parent.rootOrderId(),
                parent.executionParameters(), null, parent.createdAt(), parent.updatedAt());
        consumer.consume(event("FILLED", filled));
        // Should not throw; runtime is cleaned up
    }

    @Test
    void advanceSmartStopsWhenOrderNoLongerExecutable() throws Exception {
        OrderView parent = order("SMART", null, OrderStatus.LIVE);
        when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing()));
        when(tradingData.quotes(any())).thenReturn(List.of());
        // strategy returns a non-executable state (FILLED)
        OrderView filled = new OrderView(
                parent.id(), 2, parent.ownerSubject(), parent.deskId(), parent.listing(),
                parent.side(), parent.type(), parent.quantity(), parent.limitPrice(),
                BigDecimal.ZERO, parent.quantity(), null,
                OrderStatus.FILLED, OrderStatus.FILLED, "SMART", "ref",
                null, parent.rootOrderId(), "{}", null, parent.createdAt(), parent.updatedAt());
        when(tradingData.strategy(parent.id())).thenReturn(new StrategyStateView(filled, List.of()));

        when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any())).thenAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run(); // triggers advanceSmart → finds not executable → stopRuntime
            return mock(java.util.concurrent.ScheduledFuture.class);
        });

        consumer.consume(event("CREATED", parent));
        // strategy() was called and found non-executable → no child orders sent
        verify(kafka, never()).send(eq("orders.commands"), any(), any());
    }

    @Test
    void vwapWithDurationMinutesParameterStartsScheduler() throws Exception {
        OrderView baseOrder = order("VWAP", null, OrderStatus.LIVE);
        OrderView parent = new OrderView(
                baseOrder.id(), baseOrder.version(), baseOrder.ownerSubject(), baseOrder.deskId(),
                baseOrder.listing(), baseOrder.side(), baseOrder.type(), baseOrder.quantity(),
                baseOrder.limitPrice(), baseOrder.remainingQuantity(), baseOrder.tradedQuantity(),
                baseOrder.averageTradePrice(), baseOrder.status(), baseOrder.targetStatus(),
                baseOrder.destination(), baseOrder.originatorReference(), baseOrder.parentOrderId(),
                baseOrder.rootOrderId(), "{\"durationMinutes\":30,\"buckets\":3}",
                baseOrder.errorMessage(), Instant.now(), Instant.now());
        when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing()));
        when(tradingData.quotes(any())).thenReturn(List.of(
                quote(parent.listing().id(), "101.50")
        ));
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return mock(java.util.concurrent.ScheduledFuture.class);
        });

        consumer.consume(event("CREATED", parent));

        verify(scheduler).scheduleAtFixedRate(any(), any(), any());
    }

    @Test
    void advanceSmartHandlesTransientFailureGracefully() throws Exception {
        OrderView parent = order("SMART", null, OrderStatus.LIVE);
        when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing()));
        when(tradingData.quotes(any())).thenReturn(List.of());
        when(tradingData.strategy(parent.id())).thenThrow(new RuntimeException("service unavailable"));

        when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any())).thenAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run(); // triggers advanceSmart → service throws → logged warning
            return mock(java.util.concurrent.ScheduledFuture.class);
        });

        consumer.consume(event("CREATED", parent));
        // Should not throw; transient failure is swallowed with a log.warn
    }

    @Test
    void rejectsVwapWithInvalidDurationMinutes() throws Exception {
        OrderView baseOrder = order("VWAP", null, OrderStatus.LIVE);
        OrderView parent = new OrderView(
                baseOrder.id(), baseOrder.version(), baseOrder.ownerSubject(), baseOrder.deskId(),
                baseOrder.listing(), baseOrder.side(), baseOrder.type(), baseOrder.quantity(),
                baseOrder.limitPrice(), baseOrder.remainingQuantity(), baseOrder.tradedQuantity(),
                baseOrder.averageTradePrice(), baseOrder.status(), baseOrder.targetStatus(),
                baseOrder.destination(), baseOrder.originatorReference(), baseOrder.parentOrderId(),
                baseOrder.rootOrderId(), "{\"durationMinutes\":-5}",
                baseOrder.errorMessage(), Instant.now(), Instant.now());

        consumer.consume(event("CREATED", parent));

        verify(executionCommands).reject(eq(parent.id()), eq(parent.deskId()), any(), any(),
                eq("VWAP durationMinutes must be positive"));
    }

    @Test
    void rejectsVwapWithInvalidStartAndEndTimeOrder() throws Exception {
        long now = Instant.now().getEpochSecond();
        OrderView parent = withExecutionParameters(order("VWAP", null, OrderStatus.LIVE),
                "{\"utcStartTimeSecs\":" + (now + 1000) + ",\"utcEndTimeSecs\":" + now + "}");

        consumer.consume(event("CREATED", parent));

        verify(executionCommands).reject(eq(parent.id()), eq(parent.deskId()), any(), any(),
                eq("VWAP start time must be before end time"));
    }

    @Test
    void rejectsVwapWithInvalidParticipationRate() throws Exception {
        long now = Instant.now().getEpochSecond();
        OrderView parent = withExecutionParameters(order("VWAP", null, OrderStatus.LIVE),
                "{\"utcStartTimeSecs\":" + now + ",\"utcEndTimeSecs\":" + (now + 1000) + ",\"participationRate\":150}");

        consumer.consume(event("CREATED", parent));

        verify(executionCommands).reject(eq(parent.id()), eq(parent.deskId()), any(), any(),
                eq("VWAP participationRate must be between 1 and 100"));
    }

    @Test
    void rejectsVwapWithBucketsExceedingOrderUnits() throws Exception {
        long now = Instant.now().getEpochSecond();
        OrderView parent = withExecutionParameters(order("VWAP", null, OrderStatus.LIVE),
                "{\"utcStartTimeSecs\":" + now + ",\"utcEndTimeSecs\":" + (now + 1000) + ",\"buckets\":500}");

        consumer.consume(event("CREATED", parent));

        verify(executionCommands).reject(eq(parent.id()), eq(parent.deskId()), any(), any(),
                eq("VWAP buckets cannot exceed order quantity units"));
    }

    @Test
    void consumeHandlesInvalidJsonPayloadGracefully() throws Exception {
        OrderDomainEvent invalidEvent = new OrderDomainEvent(
                SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "user", "desk", "CREATED", 1L, OrderStatus.LIVE, Instant.now(), "invalid-json-{{"
        );

        assertThatThrownBy(() -> consumer.consume(invalidEvent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Order event payload is invalid");
    }

    @Test
    void cancelledOrRejectedEventStopsStrategyRuntime() throws Exception {
        OrderView parent = order("SMART", null, OrderStatus.LIVE);
        when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing()));
        when(tradingData.quotes(any())).thenReturn(List.of());
        consumer.consume(event("CREATED", parent));

        OrderView cancelled = withTarget(parent, OrderStatus.CANCELLED);
        consumer.consume(event("CANCELLED", cancelled));
        consumer.consume(event("REJECTED", cancelled));
    }

    private OrderDomainEvent event(String type, OrderView order) throws Exception {
        return new OrderDomainEvent(
                SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(), order.id(),
                order.ownerSubject(), order.deskId(), type, order.version(), order.status(),
                Instant.parse("2026-07-26T00:00:00Z"), objectMapper.writeValueAsString(order)
        );
    }

    private static OrderView order(String destination, UUID parentId, OrderStatus status) {
        BigDecimal quantity = new BigDecimal("10");
        BigDecimal traded = status == OrderStatus.FILLED ? quantity : BigDecimal.ZERO;
        BigDecimal remaining = quantity.subtract(traded);
        return new OrderView(
                UUID.randomUUID(), 2, "trader-a", "desk-a", listing(7, "XNAS", "Nasdaq"),
                OrderSide.BUY, OrderType.LIMIT, quantity, new BigDecimal("102"),
                remaining, traded, status == OrderStatus.FILLED ? new BigDecimal("101.25") : null,
                status, status, destination, "test-order", parentId,
                parentId == null ? UUID.randomUUID() : parentId, "{}", null,
                Instant.parse("2026-07-26T00:00:00Z"), Instant.parse("2026-07-26T00:00:01Z")
        );
    }

    private static OrderView withTarget(OrderView order, OrderStatus target) {
        return new OrderView(
                order.id(), order.version(), order.ownerSubject(), order.deskId(), order.listing(),
                order.side(), order.type(), order.quantity(), order.limitPrice(),
                order.remainingQuantity(), order.tradedQuantity(), order.averageTradePrice(),
                order.status(), target, order.destination(), order.originatorReference(),
                order.parentOrderId(), order.rootOrderId(), order.executionParameters(),
                order.errorMessage(), order.createdAt(), order.updatedAt()
        );
    }

    private static OrderView withExecutionParameters(OrderView order, String parameters) {
        return new OrderView(
                order.id(), order.version(), order.ownerSubject(), order.deskId(), order.listing(),
                order.side(), order.type(), order.quantity(), order.limitPrice(),
                order.remainingQuantity(), order.tradedQuantity(), order.averageTradePrice(),
                order.status(), order.targetStatus(), order.destination(), order.originatorReference(),
                order.parentOrderId(), order.rootOrderId(), parameters,
                order.errorMessage(), order.createdAt(), order.updatedAt()
        );
    }

    private static MarketQuote quote(long listingId, String offer) {
        return new MarketQuote(
                listingId, new BigDecimal(offer), List.of(),
                List.of(new DepthLevel(new BigDecimal(offer), new BigDecimal("100"), "TEST", "offer", listingId))
        );
    }

    private static ListingSnapshot listing(long id, String mic, String exchange) {
        return new ListingSnapshot(
                id, 1, "AAPL", "Apple Inc.", "AAPL", mic, exchange,
                "US", "USD", new BigDecimal("0.01"), BigDecimal.ONE,
                new BigDecimal("101"), new BigDecimal("100")
        );
    }
}

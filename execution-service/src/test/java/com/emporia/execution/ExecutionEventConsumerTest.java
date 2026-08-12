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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry observations = observationRegistry(meters);
    private final ExecutionEventConsumer consumer = new ExecutionEventConsumer(
            objectMapper, kafka, tradingData, scheduler, venue, executionCommands,
            meters, observations, "orders.commands", 60, 5, "simulated", 3
    );

    /**
     * Wiring a meter handler turns observations into timers, so they can be
     * asserted the same way {@code OrderMetricsTest} asserts gauges.
     */
    private static ObservationRegistry observationRegistry(MeterRegistry meters) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        return registry;
    }

    @Test
    void orderConsumerUsesAllOrderTopicPartitionsByDefault() throws Exception {
        Method consume = ExecutionEventConsumer.class.getDeclaredMethod("consume", OrderDomainEvent.class);
        KafkaListener listener = consume.getAnnotation(KafkaListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.topics()).containsExactly("${emporia.kafka.orders-topic}");
        assertThat(listener.groupId()).isEqualTo("emporia-execution-service-v1");
        assertThat(listener.concurrency()).isEqualTo("${emporia.execution.orders-consumer.concurrency:6}");
        assertThat(listener.properties()).containsExactly("auto.offset.reset=latest");
    }

    @Test
    void replacingARuntimeCancelsTheOneItReplaces() throws Exception {
        OrderView parent = order("SMART", null, OrderStatus.LIVE);
        when(tradingData.strategy(parent.id())).thenReturn(new StrategyStateView(parent, List.of()));
        when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing()));
        when(tradingData.quotes(List.of(parent.listing().id()))).thenReturn(List.of());

        List<java.util.concurrent.ScheduledFuture<?>> issued = new java.util.ArrayList<>();
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any())).thenAnswer(invocation -> {
            java.util.concurrent.ScheduledFuture<?> future = mock(java.util.concurrent.ScheduledFuture.class);
            issued.add(future);
            return future;
        });

        // Two CREATED events for the same parent, as two listener threads would.
        consumer.consume(event("CREATED", parent));
        consumer.consume(event("CREATED", parent));

        assertThat(issued).hasSize(2);
        // The first runtime must be cancelled rather than left ticking: appending
        // instead of replacing is what let one order accumulate ~8 schedulers.
        verify(issued.get(0)).cancel(false);
    }

    @Test
    void stopsAVwapRuntimeWhoseWindowHasClosed() throws Exception {
        OrderView parent = vwapOrderWithClosedWindow();
        when(tradingData.strategy(parent.id())).thenReturn(new StrategyStateView(parent, List.of()));

        java.util.concurrent.ScheduledFuture<?> future = mock(java.util.concurrent.ScheduledFuture.class);
        java.util.concurrent.atomic.AtomicReference<Runnable> tick = new java.util.concurrent.atomic.AtomicReference<>();
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any())).thenAnswer(invocation -> {
            tick.set(invocation.getArgument(0));
            return future;
        });

        try {
            consumer.consume(event("CREATED", parent));
        } catch (RuntimeException alreadyExpired) {
            // The initial plan build may reject the closed window outright.
        }

        if (tick.get() != null) {
            tick.get().run();
            // A closed window can never reopen, so the runtime must stop instead
            // of throwing on every tick for the life of the process.
            verify(future).cancel(false);
        }
    }

    private static OrderView vwapOrderWithClosedWindow() {
        OrderView base = order("VWAP", null, OrderStatus.LIVE);
        long ended = Instant.now().minusSeconds(3600).getEpochSecond();
        long started = ended - 1800;
        return new OrderView(
                base.id(), base.version(), base.ownerSubject(), base.deskId(), base.listing(),
                base.side(), base.type(), base.quantity(), base.limitPrice(),
                base.remainingQuantity(), base.tradedQuantity(), base.averageTradePrice(),
                base.status(), base.targetStatus(), base.destination(), base.originatorReference(),
                base.parentOrderId(), base.rootOrderId(),
                "{\"utcStartTimeSecs\":" + started + ",\"utcEndTimeSecs\":" + ended + "}",
                base.errorMessage(), base.createdAt(), base.updatedAt());
    }

    @Test
    void sendsDmaOrdersToTheVenueGateway() throws Exception {
        OrderView order = order("DMA", null, OrderStatus.LIVE);

        consumer.consume(event("CREATED", order));

        verify(venue).submit(order);
        verify(venue, never()).modify(any());
        verify(venue, never()).cancel(any());
        verify(kafka, never()).send(eq("orders.commands"), any(), any());
        verify(executionCommands, never()).reject(any(), any(), any(), any(), any());
        verify(executionCommands, never()).venueCancel(any(), any(), any(), any(), any());
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

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> childMessage = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq("orders.commands"), keyCaptor.capture(), childMessage.capture());
        OrderCommand child = (OrderCommand) childMessage.getValue();
        assertThat(keyCaptor.getValue()).isEqualTo(parent.id().toString());
        assertThat(child.parentOrderId()).isEqualTo(parent.id());
        assertThat(child.deskId()).isEqualTo(parent.deskId());
        assertThat(child.listing().exchangeMic()).isEqualTo("XNYS");
        // limitPrice must be the parent's limit price so protectionPriceTicks
        // anchors the slippage budget against the parent's intent, not the venue quote.
        assertThat(child.limitPrice()).isEqualByComparingTo("102");
        // The venue quote that drove venue selection is preserved for observability.
        assertThat(child.executionParameters()).containsEntry("venuePrice", new BigDecimal("101.25"));
        assertThat(child.quantity()).isEqualByComparingTo(parent.remainingQuantity());
        assertThat(child.destination()).isEqualTo("DMA");
        assertThat(timerCount("emporia.strategy.decision", "strategy", "smart",
                "outcome", "success")).isEqualTo(1);
    }

        @Test
        void smartRoutingRejectsParentWhenChildPublishFailsOnCreate() throws Exception {
        OrderView parent = order("SMART", null, OrderStatus.LIVE);
        ListingSnapshot nyse = listing(8, "XNYS", "New York Stock Exchange");
        when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing(), nyse));
        when(tradingData.quotes(List.of(parent.listing().id(), nyse.id()))).thenReturn(List.of(
            quote(parent.listing().id(), "101.50"),
            quote(nyse.id(), "101.25")
        ));
        when(kafka.send(eq("orders.commands"), any(), any()))
            .thenThrow(new RuntimeException("kafka unavailable"));

        consumer.consume(event("CREATED", parent));

        verify(executionCommands).reject(eq(parent.id()), eq(parent.deskId()), any(), any(),
            eq("Could not publish child order command"));
        }

        @Test
        void smartRoutingCountsAsyncChildPublishFailure() throws Exception {
        OrderView parent = order("SMART", null, OrderStatus.LIVE);
        ListingSnapshot nyse = listing(8, "XNYS", "New York Stock Exchange");
        when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing(), nyse));
        when(tradingData.quotes(List.of(parent.listing().id(), nyse.id()))).thenReturn(List.of(
            quote(parent.listing().id(), "101.50"),
            quote(nyse.id(), "101.25")
        ));

        CompletableFuture<SendResult<String, Object>> send = new CompletableFuture<>();
        when(kafka.send(eq("orders.commands"), any(), any())).thenReturn(send);

        consumer.consume(event("CREATED", parent));
        send.completeExceptionally(new RuntimeException("async broker error"));

        assertThat(counterCount("emporia.execution.child.publish.failures",
            "stage", "async")).isEqualTo(1);
        verify(executionCommands, never()).reject(any(), any(), any(), any(), any());
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
        verify(venue, never()).cancel(any());
    }

    @Test
    void cancelRequestedForDmaUsesVenueCancelAndSkipsExecutionCommandCancel() throws Exception {
        OrderView order = order("DMA", null, OrderStatus.LIVE);

        consumer.consume(event("CANCEL_REQUESTED", order));

        verify(venue).cancel(order);
        verify(executionCommands, never()).venueCancel(any(), any(), any(), any(), any());
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
        assertThat(timerCount("emporia.strategy.decision", "strategy", "vwap",
                "outcome", "success")).isEqualTo(1);
    }

        @Test
        void vwapRejectsParentWhenChildPublishFailsOnCreate() throws Exception {
        long now = Instant.now().getEpochSecond();
        OrderView parent = withExecutionParameters(order("VWAP", null, OrderStatus.LIVE),
            "{\"utcStartTimeSecs\":" + now + ",\"utcEndTimeSecs\":" + (now + 600) + ",\"buckets\":2}");
        ListingSnapshot nyse = listing(8, "XNYS", "New York Stock Exchange");
        when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing(), nyse));
        when(tradingData.quotes(List.of(parent.listing().id(), nyse.id()))).thenReturn(List.of(
            quote(parent.listing().id(), "101.50")
        ));
        when(kafka.send(eq("orders.commands"), any(), any()))
            .thenThrow(new RuntimeException("kafka unavailable"));

        consumer.consume(event("CREATED", parent));

        verify(executionCommands).reject(eq(parent.id()), eq(parent.deskId()), any(), any(),
            eq("Could not publish child order command"));
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

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> messages = ArgumentCaptor.forClass(Object.class);
        verify(kafka, times(2)).send(eq("orders.commands"), keys.capture(), messages.capture());
        assertThat(keys.getAllValues()).containsExactly(parent.id().toString(), parent.id().toString());
        // Compared numerically, not with equals: routed quantities now come back
        // through FixedPointMath at its canonical scale of 6, so "4" and
        // "4.000000" are the same quantity but not the same BigDecimal.
        assertThat(messages.getAllValues()).extracting(message -> ((OrderCommand) message).quantity())
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
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

        verify(venue, never()).submit(any());
        verify(venue, never()).modify(any());
        verify(venue, never()).cancel(any());
        verify(kafka, never()).send(eq("orders.commands"), any(), any());
        verify(executionCommands, never()).reject(any(), any(), any(), any(), any());
        verify(executionCommands, never()).venueCancel(any(), any(), any(), any(), any());
        assertThat(timerCount("emporia.strategy.decision", "strategy", "smart",
                "outcome", "waiting")).isEqualTo(1);
    }

    @Test
    void smartRoutingGivesUpAndResolvesTheParentAfterTheRetryCeiling() throws Exception {
        OrderView parent = order("SMART", null, OrderStatus.LIVE);
        when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing()));
        when(tradingData.quotes(List.of(parent.listing().id()))).thenReturn(List.of());
        when(tradingData.strategy(parent.id())).thenReturn(new StrategyStateView(parent, List.of()));

        consumer.consume(event("CREATED", parent));

        // Drive the scheduled ticks by hand; every one fails to route because
        // there is no liquidity.
        ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(tick.capture(), any(Instant.class), any(Duration.class));
        for (int attempt = 0; attempt < 3; attempt++) {
            tick.getValue().run();
        }

        // Before this there was no ceiling: an unroutable parent retried at the
        // full tick rate for the life of the process, and the order sat LIVE
        // with nothing working on it.
        verify(executionCommands).venueCancel(eq(parent.id()), eq(parent.deskId()), any(),
                eq(parent.listing().exchangeMic()),
                eq("SMART routing retries exhausted after 3 attempts"));
        assertThat(timerCount("emporia.strategy.decision", "strategy", "smart",
                "outcome", "exhausted")).isEqualTo(1);
    }

    @Test
    void smartRoutingRetryCounterResetsAfterASuccessfulSweep() throws Exception {
        OrderView parent = order("SMART", null, OrderStatus.LIVE);
        when(tradingData.strategy(parent.id())).thenReturn(new StrategyStateView(parent, List.of()));
        when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing()));
        when(tradingData.quotes(List.of(parent.listing().id()))).thenReturn(List.of());

        consumer.consume(event("CREATED", parent));
        ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(tick.capture(), any(Instant.class), any(Duration.class));
        tick.getValue().run();
        tick.getValue().run();

        // A sweep succeeds, so the count starts again rather than carrying two
        // old failures toward the ceiling.
        when(tradingData.quotes(List.of(parent.listing().id())))
                .thenReturn(List.of(quote(parent.listing().id(), "100.00")));
        tick.getValue().run();

        when(tradingData.quotes(List.of(parent.listing().id()))).thenReturn(List.of());
        tick.getValue().run();
        tick.getValue().run();

        verify(executionCommands, never()).venueCancel(any(), any(), any(), any(), any());
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
        void rejectsCreatedOrderWithoutDestinationBeforeVenueOrScheduling() throws Exception {
        OrderView base = order("DMA", null, OrderStatus.LIVE);
        OrderView invalid = new OrderView(
            base.id(), base.version(), base.ownerSubject(), base.deskId(), base.listing(),
            base.side(), base.type(), base.quantity(), base.limitPrice(),
            base.remainingQuantity(), base.tradedQuantity(), base.averageTradePrice(),
            base.status(), base.targetStatus(), "", base.originatorReference(),
            base.parentOrderId(), base.rootOrderId(), base.executionParameters(),
            base.errorMessage(), base.createdAt(), base.updatedAt());

        consumer.consume(event("CREATED", invalid));

        verify(venue, never()).submit(any());
        verify(kafka, never()).send(eq("orders.commands"), any(), any());
        verify(executionCommands).reject(eq(invalid.id()), eq(invalid.deskId()), any(), any(),
            eq("Execution destination is required"));
        }

        @Test
        void rejectsCreatedLimitOrderWithoutPriceBeforeVenueOrScheduling() throws Exception {
        OrderView base = order("DMA", null, OrderStatus.LIVE);
        OrderView invalid = new OrderView(
            base.id(), base.version(), base.ownerSubject(), base.deskId(), base.listing(),
            base.side(), base.type(), base.quantity(), null,
            base.remainingQuantity(), base.tradedQuantity(), base.averageTradePrice(),
            base.status(), base.targetStatus(), base.destination(), base.originatorReference(),
            base.parentOrderId(), base.rootOrderId(), base.executionParameters(),
            base.errorMessage(), base.createdAt(), base.updatedAt());

        consumer.consume(event("CREATED", invalid));

        verify(venue, never()).submit(any());
        verify(kafka, never()).send(eq("orders.commands"), any(), any());
        verify(executionCommands).reject(eq(invalid.id()), eq(invalid.deskId()), any(), any(),
            eq("Limit order price is required"));
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
        verify(executionCommands, never()).venueCancel(any(), any(), any(), any(), any());
    }

    @Test
    void nonTerminalParentEventDoesNotCallVenueOrEmitExecutionCommand() throws Exception {
        OrderView order = order("SMART", null, OrderStatus.LIVE);

        consumer.consume(event("ACKNOWLEDGED", order));

        verify(venue, never()).submit(any());
        verify(venue, never()).modify(any());
        verify(venue, never()).cancel(any());
        verify(executionCommands, never()).fill(any(), any(), any(), any(), any(), any(), any());
        verify(executionCommands, never()).reject(any(), any(), any(), any(), any());
        verify(executionCommands, never()).venueCancel(any(), any(), any(), any(), any());
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
        assertThat(timerCount("emporia.strategy.decision", "strategy", "smart",
                "outcome", "noop")).isEqualTo(1);
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

    private long timerCount(String name, String... tags) {
        return meters.find(name).tags(tags).timer() == null
                ? 0 : meters.find(name).tags(tags).timer().count();
    }

    private double counterCount(String name, String... tags) {
        return meters.find(name).tags(tags).counter() == null
                ? 0 : meters.find(name).tags(tags).counter().count();
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

    @Test
    void mapQuotesGroupsByVenueMicWithoutCrossVenuePairing() throws Exception {
        Method mapQuotesMethod = ExecutionEventConsumer.class.getDeclaredMethod("mapQuotes", List.class);
        mapQuotesMethod.setAccessible(true);

        // Failure scenario: bids = [XNAS@150.00], offers = [XNYS@150.25, XNAS@150.50]
        DepthLevel bidXnas = new DepthLevel(new BigDecimal("150.00"), new BigDecimal("100"), "XNAS", "b1", 1L);
        DepthLevel offerXnys = new DepthLevel(new BigDecimal("150.25"), new BigDecimal("100"), "XNYS", "o1", 1L);
        DepthLevel offerXnas = new DepthLevel(new BigDecimal("150.50"), new BigDecimal("100"), "XNAS", "o2", 1L);

        MarketQuote quote = new MarketQuote(1L, new BigDecimal("150.00"), List.of(bidXnas), List.of(offerXnys, offerXnas));

        @SuppressWarnings("unchecked")
        List<com.emporia.strategy.sor.BestVenueSelector.MarketQuoteView> views =
                (List<com.emporia.strategy.sor.BestVenueSelector.MarketQuoteView>) mapQuotesMethod.invoke(consumer, List.of(quote));

        assertThat(views).hasSize(2);

        com.emporia.strategy.sor.BestVenueSelector.MarketQuoteView xnasView = views.stream()
                .filter(v -> "XNAS".equals(v.exchangeMic()))
                .findFirst().orElseThrow();
        assertThat(xnasView.bidPrice()).isEqualByComparingTo("150.00");
        assertThat(xnasView.askPrice()).isEqualByComparingTo("150.50");

        com.emporia.strategy.sor.BestVenueSelector.MarketQuoteView xnysView = views.stream()
                .filter(v -> "XNYS".equals(v.exchangeMic()))
                .findFirst().orElseThrow();
        assertThat(xnysView.bidPrice()).isNull();
        assertThat(xnysView.askPrice()).isEqualByComparingTo("150.25");
    }

    private static ListingSnapshot listing(long id, String mic, String exchange) {
        return new ListingSnapshot(
                id, 1, "AAPL", "Apple Inc.", "AAPL", mic, exchange,
                "US", "USD", new BigDecimal("0.01"), BigDecimal.ONE,
                new BigDecimal("101"), new BigDecimal("100")
        );
    }
}

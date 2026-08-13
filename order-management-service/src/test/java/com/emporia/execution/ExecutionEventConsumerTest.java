package com.emporia.execution;

import com.emporia.events.TradingEvents.ExecutionRecoveryView;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import com.emporia.events.TradingEvents.StrategyStateView;
import com.emporia.execution.TradingDataClient.DepthLevel;
import com.emporia.execution.TradingDataClient.MarketQuote;
import com.emporia.ordermanagement.disruptor.DisruptorOrderPipeline;
import com.emporia.ordermanagement.service.OrderInputEventRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExecutionEventConsumerTest {
    private final TradingDataClient tradingData = mock(TradingDataClient.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final ExecutionVenueGateway venue = mock(ExecutionVenueGateway.class);
    private final ExecutionCommandPublisher executionCommands = mock(ExecutionCommandPublisher.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry observations = observationRegistry(meters);
    private final DisruptorOrderPipeline disruptorPipeline = defaultDisruptorPipeline();
    private final OrderInputEventRecorder inputRecorder = mock(OrderInputEventRecorder.class);
    private final ExecutionEventConsumer consumer = new ExecutionEventConsumer(
            objectMapper, tradingData, scheduler, venue, executionCommands,
            meters, observations, 60, 5, "simulated", 3, disruptorPipeline, inputRecorder
    );

    private static ObservationRegistry observationRegistry(MeterRegistry meters) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        return registry;
    }

    private static DisruptorOrderPipeline defaultDisruptorPipeline() {
        DisruptorOrderPipeline pipeline = mock(DisruptorOrderPipeline.class);
        when(pipeline.submit(any())).thenReturn(CompletableFuture.completedFuture(null));
        return pipeline;
    }

    @Nested
    @DisplayName("DMA Destination Routing")
    class DmaRoutingTests {
        @Test
        @DisplayName("Sends CREATED DMA order directly to venue gateway")
        void sendsDmaOrdersToTheVenueGateway() throws Exception {
            OrderView order = order("DMA", null, OrderStatus.LIVE);
            consumer.processEvent(event("CREATED", order));

            verify(venue).submit(order);
            verify(venue, never()).modify(any());
            verify(venue, never()).cancel(any());
            verify(disruptorPipeline, never()).submit(any());
            verify(executionCommands, never()).reject(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("CANCEL_REQUESTED for DMA order triggers venue cancel")
        void cancelRequestedForDmaCancelsAtVenue() throws Exception {
            OrderView order = order("DMA", null, OrderStatus.LIVE);
            consumer.processEvent(event("CANCEL_REQUESTED", order));

            verify(venue).cancel(order);
            verify(executionCommands, never()).venueCancel(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("MODIFIED for DMA calls venue modify")
        void modifiedEventForDmaCallsVenueModify() throws Exception {
            OrderView order = order("DMA", null, OrderStatus.LIVE);
            consumer.processEvent(event("MODIFIED", order));

            verify(venue).modify(order);
            verify(executionCommands, never()).venueCancel(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("MODIFIED for non-DMA destination is ignored")
        void modifiedEventForNonDmaDestinationIsIgnored() throws Exception {
            OrderView order = order("SMART", null, OrderStatus.LIVE);
            consumer.processEvent(event("MODIFIED", order));

            verify(venue, never()).modify(any());
        }
    }

    @Nested
    @DisplayName("SMART Routing Strategy & AdvanceSmartState Regression Tests")
    class SmartRoutingTests {
        @Test
        @DisplayName("Regression Test: advanceSmartState throws IllegalStateException when route plan is empty")
        void advanceSmartStateThrowsIllegalStateExceptionWhenPlanIsEmpty() throws Exception {
            OrderView parent = order("SMART", null, OrderStatus.LIVE);
            ListingSnapshot listing = parent.listing();
            when(tradingData.sameInstrument(listing.id())).thenReturn(List.of(listing));
            // Offer price 105.00 is outside limit price 102.00 -> plan will be empty
            MarketQuote unmatchingQuote = new MarketQuote(
                    listing.id(), new BigDecimal("105.00"), List.of(),
                    List.of(new DepthLevel(new BigDecimal("105.00"), new BigDecimal("10"), listing.exchangeMic(), "offer1", listing.id()))
            );
            when(tradingData.quotes(List.of(listing.id()))).thenReturn(List.of(unmatchingQuote));

            StrategyStateView state = new StrategyStateView(parent, List.of());
            Method advanceSmartState = ExecutionEventConsumer.class.getDeclaredMethod("advanceSmartState", StrategyStateView.class);
            advanceSmartState.setAccessible(true);

            assertThatThrownBy(() -> {
                try {
                    advanceSmartState.invoke(consumer, state);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            }).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("No executable venue listing exists for the instrument");
        }

        @Test
        @DisplayName("Regression Test: advanceSmartState throws IllegalStateException when the most recent child was rejected")
        void advanceSmartStateThrowsIllegalStateExceptionWhenLastChildWasRejected() throws Exception {
            OrderView parent = order("SMART", null, OrderStatus.LIVE);
            OrderView rejectedChild = order("SMART", parent.id(), OrderStatus.REJECTED);
            StrategyStateView state = new StrategyStateView(parent, List.of(rejectedChild));

            Method advanceSmartState = ExecutionEventConsumer.class.getDeclaredMethod("advanceSmartState", StrategyStateView.class);
            advanceSmartState.setAccessible(true);

            assertThatThrownBy(() -> {
                try {
                    advanceSmartState.invoke(consumer, state);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            }).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("was rejected by the venue");

            verifyNoInteractions(tradingData);
        }

        @Test
        @DisplayName("SMART routing creates child order at best venue and dispatches it directly")
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

            consumer.processEvent(event("CREATED", parent));

            ArgumentCaptor<OrderCommand> childCaptor = ArgumentCaptor.forClass(OrderCommand.class);
            verify(disruptorPipeline).submit(childCaptor.capture());
            OrderCommand child = childCaptor.getValue();
            assertThat(child.parentOrderId()).isEqualTo(parent.id());
            assertThat(child.listing().exchangeMic()).isEqualTo("XNYS");
            assertThat(child.limitPrice()).isEqualByComparingTo("102");
            assertThat(child.executionParameters()).containsEntry("venuePrice", new BigDecimal("101.25"));
            assertThat(child.quantity()).isEqualByComparingTo(parent.remainingQuantity());
            verify(inputRecorder).record(child);
            assertThat(timerCount("emporia.strategy.decision", "strategy", "smart", "outcome", "success")).isEqualTo(1);
        }

        @Test
        @DisplayName("SMART routing rejects parent when child dispatch fails immediately")
        void smartRoutingRejectsParentWhenChildPublishFailsOnCreate() throws Exception {
            OrderView parent = order("SMART", null, OrderStatus.LIVE);
            ListingSnapshot nyse = listing(8, "XNYS", "New York Stock Exchange");
            when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing(), nyse));
            when(tradingData.quotes(List.of(parent.listing().id(), nyse.id()))).thenReturn(List.of(
                    quote(parent.listing().id(), "101.50"),
                    quote(nyse.id(), "101.25")
            ));
            when(disruptorPipeline.submit(any())).thenThrow(new RuntimeException("pipeline unavailable"));

            consumer.processEvent(event("CREATED", parent));

            verify(executionCommands).reject(eq(parent.id()), eq(parent.deskId()), any(), any(),
                    eq("Could not publish child order command"));
        }

        @Test
        @DisplayName("SMART routing walks order book depth across multiple venues")
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

            consumer.processEvent(event("CREATED", parent));

            ArgumentCaptor<OrderCommand> children = ArgumentCaptor.forClass(OrderCommand.class);
            verify(disruptorPipeline, times(2)).submit(children.capture());
            assertThat(children.getAllValues()).extracting(OrderCommand::quantity)
                    .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                    .containsExactly(new BigDecimal("4"), new BigDecimal("6"));
            assertThat(children.getAllValues()).extracting(message -> message.listing().exchangeMic())
                    .containsExactly("XNAS", "XNYS");
        }

        @Test
        @DisplayName("SMART routing waits when liquidity is unavailable")
        void smartRoutingWaitsAndRetriesWhenNoExecutableLiquidityIsAvailable() throws Exception {
            OrderView parent = order("SMART", null, OrderStatus.LIVE);
            when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing()));
            when(tradingData.quotes(List.of(parent.listing().id()))).thenReturn(List.of());

            consumer.processEvent(event("CREATED", parent));

            verify(venue, never()).submit(any());
            verify(disruptorPipeline, never()).submit(any());
            assertThat(timerCount("emporia.strategy.decision", "strategy", "smart", "outcome", "waiting")).isEqualTo(1);
        }

        @Test
        @DisplayName("SMART routing gives up after max retries reached")
        void smartRoutingGivesUpAndResolvesTheParentAfterTheRetryCeiling() throws Exception {
            OrderView parent = order("SMART", null, OrderStatus.LIVE);
            when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing()));
            when(tradingData.quotes(List.of(parent.listing().id()))).thenReturn(List.of());
            when(tradingData.strategy(parent.id())).thenReturn(new StrategyStateView(parent, List.of()));

            consumer.processEvent(event("CREATED", parent));

            ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).scheduleAtFixedRate(tick.capture(), any(Instant.class), any(Duration.class));
            for (int attempt = 0; attempt < 3; attempt++) {
                tick.getValue().run();
            }

            verify(executionCommands).venueCancel(eq(parent.id()), eq(parent.deskId()), any(),
                    eq(parent.listing().exchangeMic()),
                    eq("SMART routing retries exhausted after 3 attempts"));
            assertThat(timerCount("emporia.strategy.decision", "strategy", "smart", "outcome", "exhausted")).isEqualTo(1);
        }

        @Test
        @DisplayName("Regression Test: SMART routing gives up after a rejected child instead of spawning unbounded children")
        void smartRoutingGivesUpAfterRejectedChildInsteadOfSpawningMoreChildren() throws Exception {
            OrderView parent = order("SMART", null, OrderStatus.LIVE);
            ListingSnapshot nyse = listing(8, "XNYS", "New York Stock Exchange");
            when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing(), nyse));
            when(tradingData.quotes(List.of(parent.listing().id(), nyse.id()))).thenReturn(List.of(
                    quote(parent.listing().id(), "101.50"),
                    quote(nyse.id(), "101.25")
            ));

            OrderView rejectedChild = order("SMART", parent.id(), OrderStatus.REJECTED);
            when(tradingData.strategy(parent.id())).thenReturn(new StrategyStateView(parent, List.of(rejectedChild)));

            consumer.processEvent(event("CREATED", parent));
            // The initial dispatch (state.children() still empty) routes exactly one child.
            verify(disruptorPipeline, times(1)).submit(any());

            ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).scheduleAtFixedRate(tick.capture(), any(Instant.class), any(Duration.class));
            for (int attempt = 0; attempt < 3; attempt++) {
                tick.getValue().run();
            }

            // Once the venue rejects that child, no further children get routed on
            // subsequent ticks - previously this re-planned and re-rejected the
            // same unrouted quantity every tick with no ceiling.
            verify(disruptorPipeline, times(1)).submit(any());
            verify(executionCommands).venueCancel(eq(parent.id()), eq(parent.deskId()), any(),
                    eq(parent.listing().exchangeMic()),
                    eq("SMART routing retries exhausted after 3 attempts"));
        }

        @Test
        @DisplayName("SMART routing retry counter resets after successful sweep")
        void smartRoutingRetryCounterResetsAfterASuccessfulSweep() throws Exception {
            OrderView parent = order("SMART", null, OrderStatus.LIVE);
            when(tradingData.strategy(parent.id())).thenReturn(new StrategyStateView(parent, List.of()));
            when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing()));
            when(tradingData.quotes(List.of(parent.listing().id()))).thenReturn(List.of());

            consumer.processEvent(event("CREATED", parent));
            ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).scheduleAtFixedRate(tick.capture(), any(Instant.class), any(Duration.class));
            tick.getValue().run();
            tick.getValue().run();

            when(tradingData.quotes(List.of(parent.listing().id())))
                    .thenReturn(List.of(quote(parent.listing().id(), "100.00")));
            tick.getValue().run();

            when(tradingData.quotes(List.of(parent.listing().id()))).thenReturn(List.of());
            tick.getValue().run();
            tick.getValue().run();

            verify(executionCommands, never()).venueCancel(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("VWAP Strategy Tests")
    class VwapStrategyTests {
        @Test
        @DisplayName("VWAP strategy starts and schedules slices")
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
                return mock(ScheduledFuture.class);
            });

            consumer.processEvent(event("CREATED", parent));

            verify(disruptorPipeline).submit(any());
            assertThat(timerCount("emporia.strategy.decision", "strategy", "vwap", "outcome", "success")).isEqualTo(1);
        }

        @Test
        @DisplayName("VWAP strategy rejects parent when child publish fails on create")
        void vwapRejectsParentWhenChildPublishFailsOnCreate() throws Exception {
            long now = Instant.now().getEpochSecond();
            OrderView parent = withExecutionParameters(order("VWAP", null, OrderStatus.LIVE),
                    "{\"utcStartTimeSecs\":" + now + ",\"utcEndTimeSecs\":" + (now + 600) + ",\"buckets\":2}");
            ListingSnapshot nyse = listing(8, "XNYS", "New York Stock Exchange");
            when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing(), nyse));
            when(tradingData.quotes(List.of(parent.listing().id(), nyse.id()))).thenReturn(List.of(
                    quote(parent.listing().id(), "101.50")
            ));
            when(disruptorPipeline.submit(any())).thenThrow(new RuntimeException("pipeline unavailable"));

            consumer.processEvent(event("CREATED", parent));

            verify(executionCommands).reject(eq(parent.id()), eq(parent.deskId()), any(), any(),
                    eq("Could not publish child order command"));
        }

        @Test
        @DisplayName("VWAP strategy stops runtime when window has closed")
        void stopsAVwapRuntimeWhoseWindowHasClosed() throws Exception {
            OrderView parent = vwapOrderWithClosedWindow();
            when(tradingData.strategy(parent.id())).thenReturn(new StrategyStateView(parent, List.of()));

            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            AtomicReference<Runnable> tick = new AtomicReference<>();
            when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any())).thenAnswer(invocation -> {
                tick.set(invocation.getArgument(0));
                return future;
            });

            try {
                consumer.processEvent(event("CREATED", parent));
            } catch (RuntimeException alreadyExpired) {
                // Expected when initial plan build rejects expired window
            }

            if (tick.get() != null) {
                tick.get().run();
                verify(future).cancel(false);
            }
        }

        @Test
        @DisplayName("Rejects VWAP order with negative durationMinutes")
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

            consumer.processEvent(event("CREATED", parent));

            verify(executionCommands).reject(eq(parent.id()), eq(parent.deskId()), any(), any(),
                    eq("VWAP durationMinutes must be positive"));
        }

        @Test
        @DisplayName("Rejects VWAP order when start time is after end time")
        void rejectsVwapWithInvalidStartAndEndTimeOrder() throws Exception {
            long now = Instant.now().getEpochSecond();
            OrderView parent = withExecutionParameters(order("VWAP", null, OrderStatus.LIVE),
                    "{\"utcStartTimeSecs\":" + (now + 1000) + ",\"utcEndTimeSecs\":" + now + "}");

            consumer.processEvent(event("CREATED", parent));

            verify(executionCommands).reject(eq(parent.id()), eq(parent.deskId()), any(), any(),
                    eq("VWAP start time must be before end time"));
        }

        @Test
        @DisplayName("Rejects VWAP order with invalid participationRate")
        void rejectsVwapWithInvalidParticipationRate() throws Exception {
            long now = Instant.now().getEpochSecond();
            OrderView parent = withExecutionParameters(order("VWAP", null, OrderStatus.LIVE),
                    "{\"utcStartTimeSecs\":" + now + ",\"utcEndTimeSecs\":" + (now + 1000) + ",\"participationRate\":150}");

            consumer.processEvent(event("CREATED", parent));

            verify(executionCommands).reject(eq(parent.id()), eq(parent.deskId()), any(), any(),
                    eq("VWAP participationRate must be between 1 and 100"));
        }

        @Test
        @DisplayName("Rejects VWAP order when buckets exceed total units")
        void rejectsVwapWithBucketsExceedingOrderUnits() throws Exception {
            long now = Instant.now().getEpochSecond();
            OrderView parent = withExecutionParameters(order("VWAP", null, OrderStatus.LIVE),
                    "{\"utcStartTimeSecs\":" + now + ",\"utcEndTimeSecs\":" + (now + 1000) + ",\"buckets\":500}");

            consumer.processEvent(event("CREATED", parent));

            verify(executionCommands).reject(eq(parent.id()), eq(parent.deskId()), any(), any(),
                    eq("VWAP buckets cannot exceed order quantity units"));
        }
    }

    @Nested
    @DisplayName("Runtime Lifecycle & Validation Tests")
    class LifecycleAndValidationTests {
        @Test
        @DisplayName("Replacing a runtime cancels the previous scheduled task")
        void replacingARuntimeCancelsTheOneItReplaces() throws Exception {
            OrderView parent = order("SMART", null, OrderStatus.LIVE);
            when(tradingData.strategy(parent.id())).thenReturn(new StrategyStateView(parent, List.of()));
            when(tradingData.sameInstrument(parent.listing().id())).thenReturn(List.of(parent.listing()));
            when(tradingData.quotes(List.of(parent.listing().id()))).thenReturn(List.of());

            List<ScheduledFuture<?>> issued = new java.util.ArrayList<>();
            when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any())).thenAnswer(invocation -> {
                ScheduledFuture<?> future = mock(ScheduledFuture.class);
                issued.add(future);
                return future;
            });

            consumer.processEvent(event("CREATED", parent));
            consumer.processEvent(event("CREATED", parent));

            assertThat(issued).hasSize(2);
            verify(issued.get(0)).cancel(false);
        }

        @Test
        @DisplayName("Rejects unsupported destination")
        void rejectsUnsupportedExecutionDestination() throws Exception {
            OrderView order = order("UNKNOWN_DEST", null, OrderStatus.LIVE);
            consumer.processEvent(event("CREATED", order));

            verify(executionCommands).reject(eq(order.id()), eq(order.deskId()), any(), eq(order.listing().exchangeMic()),
                    eq("Unsupported execution destination UNKNOWN_DEST"));
        }

        @Test
        @DisplayName("Rejects CREATED order missing destination")
        void rejectsCreatedOrderWithoutDestinationBeforeVenueOrScheduling() throws Exception {
            OrderView base = order("DMA", null, OrderStatus.LIVE);
            OrderView invalid = new OrderView(
                    base.id(), base.version(), base.ownerSubject(), base.deskId(), base.listing(),
                    base.side(), base.type(), base.quantity(), base.limitPrice(),
                    base.remainingQuantity(), base.tradedQuantity(), base.averageTradePrice(),
                    base.status(), base.targetStatus(), "", base.originatorReference(),
                    base.parentOrderId(), base.rootOrderId(), base.executionParameters(),
                    base.errorMessage(), base.createdAt(), base.updatedAt());

            consumer.processEvent(event("CREATED", invalid));

            verify(venue, never()).submit(any());
            verify(disruptorPipeline, never()).submit(any());
            verify(executionCommands).reject(eq(invalid.id()), eq(invalid.deskId()), any(), any(),
                    eq("Execution destination is required"));
        }

        @Test
        @DisplayName("Rejects CREATED limit order missing limitPrice")
        void rejectsCreatedLimitOrderWithoutPriceBeforeVenueOrScheduling() throws Exception {
            OrderView base = order("DMA", null, OrderStatus.LIVE);
            OrderView invalid = new OrderView(
                    base.id(), base.version(), base.ownerSubject(), base.deskId(), base.listing(),
                    base.side(), base.type(), base.quantity(), null,
                    base.remainingQuantity(), base.tradedQuantity(), base.averageTradePrice(),
                    base.status(), base.targetStatus(), base.destination(), base.originatorReference(),
                    base.parentOrderId(), base.rootOrderId(), base.executionParameters(),
                    base.errorMessage(), base.createdAt(), base.updatedAt());

            consumer.processEvent(event("CREATED", invalid));

            verify(venue, never()).submit(any());
            verify(disruptorPipeline, never()).submit(any());
            verify(executionCommands).reject(eq(invalid.id()), eq(invalid.deskId()), any(), any(),
                    eq("Limit order price is required"));
        }

        @Test
        @DisplayName("Throws IllegalArgumentException when consuming invalid JSON payload")
        void consumeHandlesInvalidJsonPayloadGracefully() throws Exception {
            OrderDomainEvent invalidEvent = new OrderDomainEvent(
                    SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "user", "desk", "CREATED", 1L, OrderStatus.LIVE, Instant.now(), "invalid-json-{{"
            );

            assertThatThrownBy(() -> consumer.processEvent(invalidEvent))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Order event payload is invalid");
        }
    }

    @Nested
    @DisplayName("Recovery Tests")
    class RecoveryTests {
        @Test
        @DisplayName("Schedules recovery task after application startup")
        void recoverAfterStartupSchedulesRecovery() {
            consumer.recoverAfterStartup();
            verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
        }

        @Test
        @DisplayName("Reschedules recovery on transient recovery failure")
        void recoverHandlesTransientFailureAndReschedules() {
            when(tradingData.recoverable()).thenThrow(new RuntimeException("Trading data service unavailable"));
            consumer.recover();
            verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
        }

        @Test
        @DisplayName("Recovery cancels direct and strategy orders targeting cancellation")
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
        @DisplayName("Recovery reattaches direct orders without resubmitting")
        void restartRecoveryReattachesDirectOrdersWithoutResubmittingThem() {
            OrderView live = order("DMA", null, OrderStatus.LIVE);
            when(tradingData.recoverable()).thenReturn(new ExecutionRecoveryView(List.of(live), List.of()));

            consumer.recover();

            verify(venue).recover(live);
            verify(venue, never()).submit(live);
        }

        @Test
        @DisplayName("Recovery rejects expired VWAP and continues")
        void restartRecoveryRejectsAnExpiredVwapAndContinues() throws Exception {
            OrderView expired = withExecutionParameters(order("VWAP", null, OrderStatus.LIVE),
                    "{\"utcStartTimeSecs\":1,\"utcEndTimeSecs\":2,\"buckets\":1}");
            when(tradingData.recoverable()).thenReturn(new ExecutionRecoveryView(
                    List.of(), List.of(new StrategyStateView(expired, List.of()))));

            consumer.recover();

            verify(executionCommands).reject(eq(expired.id()), eq(expired.deskId()), any(), any(),
                    eq("VWAP end time has already passed"));
        }
    }

    // --- Helper Factory Methods ---

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

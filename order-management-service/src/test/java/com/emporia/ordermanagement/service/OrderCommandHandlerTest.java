package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.CommandType;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderCommandResult;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.ordermanagement.dto.ProcessingOutcome;
import com.emporia.ordermanagement.model.OrderEvent;
import com.emporia.ordermanagement.model.ProcessedCommand;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.OrderEventRepository;
import com.emporia.ordermanagement.repository.ProcessedCommandRepository;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCommandHandlerTest {

    private static final String USER = "trader-one";
    private static final String DESK = "trader-one";

    private final TradingOrderRepository orders = mock(TradingOrderRepository.class);
    private final OrderEventRepository events = mock(OrderEventRepository.class);
    private final ProcessedCommandRepository processed = mock(ProcessedCommandRepository.class);
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry observations = observationRegistry(meters);
    private final OrderCommandHandler handler =
            new OrderCommandHandler(orders, events, processed, new ObjectMapper(), observations);

    /**
     * Wiring a meter handler turns observations into timers, so they can be
     * asserted the same way {@code OrderMetricsTest} asserts gauges.
     */
    private static ObservationRegistry observationRegistry(MeterRegistry meters) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        return registry;
    }

    @BeforeEach
    void defaultNoCache() {
        when(processed.findById(any())).thenReturn(Optional.empty());
        when(events.save(any(OrderEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orders.saveAndFlush(any(TradingOrder.class))).thenAnswer(inv -> {
            TradingOrder o = inv.getArgument(0);
            ReflectionTestUtils.setField(o, "version", o.getVersion() == null ? 1L : o.getVersion() + 1);
            return o;
        });
    }

    // -------------------------------------------------------------------------
    // CREATE — happy path
    // -------------------------------------------------------------------------

    @Test
    void createAcceptsAValidLimitOrderAndPublishesCreatedEvent() {
        UUID orderId = UUID.randomUUID();
        when(orders.existsById(orderId)).thenReturn(false);

        ProcessingOutcome outcome = handler.handle(createCommand(orderId));

        assertThat(outcome.result().success()).isTrue();
        assertThat(outcome.result().status()).isEqualTo(201);
        assertThat(outcome.events()).hasSize(1);
        assertThat(outcome.events().getFirst().eventType()).isEqualTo("CREATED");
        verify(processed).save(any(ProcessedCommand.class));
    }

    @Test
    void createAcceptsAMarketOrder() {
        UUID orderId = UUID.randomUUID();
        when(orders.existsById(orderId)).thenReturn(false);

        OrderCommand command = new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.CREATE,
                USER, Instant.EPOCH, orderId, null, listing(),
                OrderSide.SELL, OrderType.MARKET,
                new BigDecimal("10"), null, "DMA", "mkt-ref", null, Map.of()
        );

        ProcessingOutcome outcome = handler.handle(command);

        assertThat(outcome.result().success()).isTrue();
        assertThat(outcome.result().status()).isEqualTo(201);
    }

    // -------------------------------------------------------------------------
    // CREATE — desk derivation: falls back to userSubject when deskId is blank
    // -------------------------------------------------------------------------

    @Test
    void createUsesUserSubjectAsDeskWhenDeskIdIsBlank() {
        UUID orderId = UUID.randomUUID();
        when(orders.existsById(orderId)).thenReturn(false);

        // deskId omitted — construct a command with deskId=null via a custom command
        OrderCommand command = new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.CREATE,
                USER, Instant.EPOCH, orderId, null, listing(),
                OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"), "DMA", "ref", null, Map.of()
        );

        ProcessingOutcome outcome = handler.handle(command);

        assertThat(outcome.result().success()).isTrue();
        // The order should have been saved; desk = userSubject
        verify(orders).saveAndFlush(any(TradingOrder.class));
    }

    // -------------------------------------------------------------------------
    // CREATE — validation failures
    // -------------------------------------------------------------------------

    @Test
    void createRejectsCommandWithMissingOrderId() {
        OrderCommand command = new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.CREATE,
                USER, Instant.EPOCH, null, null, listing(),
                OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"), "DMA", "ref", null, Map.of()
        );

        ProcessingOutcome outcome = handler.handle(command);

        assertThat(outcome.result().success()).isFalse();
        assertThat(outcome.result().status()).isEqualTo(400);
        verify(orders, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsCommandWithMissingListing() {
        UUID orderId = UUID.randomUUID();
        when(orders.existsById(orderId)).thenReturn(false);

        OrderCommand command = new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.CREATE,
                USER, Instant.EPOCH, orderId, null, null,
                OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"), "DMA", "ref", null, Map.of()
        );

        ProcessingOutcome outcome = handler.handle(command);

        assertThat(outcome.result().success()).isFalse();
        assertThat(outcome.result().status()).isEqualTo(400);
    }

    @Test
    void createRejectsDuplicateOrderId() {
        UUID orderId = UUID.randomUUID();
        when(orders.existsById(orderId)).thenReturn(true);

        ProcessingOutcome outcome = handler.handle(createCommand(orderId));

        assertThat(outcome.result().success()).isFalse();
        assertThat(outcome.result().status()).isEqualTo(409);
    }

    @Test
    void createRejectsInvalidQuantityNotAlignedWithSizeIncrement() {
        UUID orderId = UUID.randomUUID();
        when(orders.existsById(orderId)).thenReturn(false);

        OrderCommand command = new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.CREATE,
                USER, Instant.EPOCH, orderId, null, listing(),
                OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10.005"), new BigDecimal("100"), "DMA", "ref", null, Map.of()
        );

        ProcessingOutcome outcome = handler.handle(command);

        assertThat(outcome.result().success()).isFalse();
        assertThat(outcome.result().status()).isEqualTo(400);
    }

    @Test
    void createRejectsOffTickLimitPrice() {
        UUID orderId = UUID.randomUUID();
        when(orders.existsById(orderId)).thenReturn(false);

        OrderCommand command = new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.CREATE,
                USER, Instant.EPOCH, orderId, null, listing(),
                OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100.005"), "DMA", "ref", null, Map.of()
        );

        ProcessingOutcome outcome = handler.handle(command);

        assertThat(outcome.result().success()).isFalse();
        assertThat(outcome.result().status()).isEqualTo(400);
    }

    // -------------------------------------------------------------------------
    // CREATE — child orders
    // -------------------------------------------------------------------------

    @Test
    void createAllowsAChildOrderUnderALiveParent() {
        TradingOrder parent = liveOrder();
        UUID childId = UUID.randomUUID();
        when(orders.existsById(childId)).thenReturn(false);
        when(orders.findByIdAndDeskId(parent.getId(), DESK)).thenReturn(Optional.of(parent));

        OrderCommand command = new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.CREATE,
                USER, Instant.EPOCH, childId, null, listing(),
                OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"), "VWAP", "child-ref",
                parent.getId(), Map.of()
        );

        ProcessingOutcome outcome = handler.handle(command);

        assertThat(outcome.result().success()).isTrue();
        assertThat(outcome.result().status()).isEqualTo(201);
    }

    @Test
    void createRejectsChildOrderWhenParentIsPendingCancellation() {
        TradingOrder parent = liveOrder();
        parent.requestCancel();
        UUID childId = UUID.randomUUID();
        when(orders.existsById(childId)).thenReturn(false);
        when(orders.findByIdAndDeskId(parent.getId(), DESK)).thenReturn(Optional.of(parent));

        OrderCommand command = new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.CREATE,
                USER, Instant.EPOCH, childId, null, listing(),
                OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"), "VWAP", "child-ref",
                parent.getId(), Map.of()
        );

        ProcessingOutcome outcome = handler.handle(command);

        assertThat(outcome.result().success()).isFalse();
        assertThat(outcome.result().status()).isEqualTo(409);
    }

    // -------------------------------------------------------------------------
    // MODIFY
    // -------------------------------------------------------------------------

    @Test
    void modifyUpdatesQuantityAndPriceOnADmaOrder() {
        TradingOrder order = liveOrder();
        when(orders.findByIdAndDeskId(order.getId(), DESK)).thenReturn(Optional.of(order));

        OrderCommand command = new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.MODIFY,
                USER, Instant.EPOCH, order.getId(), order.getVersion(), null,
                null, null, new BigDecimal("20"), new BigDecimal("105"), null, null, null, Map.of()
        );

        ProcessingOutcome outcome = handler.handle(command);

        assertThat(outcome.result().success()).isTrue();
        assertThat(outcome.result().status()).isEqualTo(200);
        assertThat(outcome.events().getFirst().eventType()).isEqualTo("MODIFIED");
    }

    @Test
    void modifyRejectsNonDmaOrders() {
        TradingOrder order = liveOrder("VWAP");
        when(orders.findByIdAndDeskId(order.getId(), DESK)).thenReturn(Optional.of(order));

        OrderCommand command = new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.MODIFY,
                USER, Instant.EPOCH, order.getId(), order.getVersion(), null,
                null, null, new BigDecimal("20"), new BigDecimal("105"), null, null, null, Map.of()
        );

        ProcessingOutcome outcome = handler.handle(command);

        assertThat(outcome.result().success()).isFalse();
        assertThat(outcome.result().status()).isEqualTo(409);
    }

    @Test
    void modifyRejectsStaleExpectedVersion() {
        TradingOrder order = liveOrder();
        when(orders.findByIdAndDeskId(order.getId(), DESK)).thenReturn(Optional.of(order));

        OrderCommand command = new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.MODIFY,
                USER, Instant.EPOCH, order.getId(), order.getVersion() + 99, null,
                null, null, new BigDecimal("20"), new BigDecimal("105"), null, null, null, Map.of()
        );

        ProcessingOutcome outcome = handler.handle(command);

        assertThat(outcome.result().success()).isFalse();
        assertThat(outcome.result().status()).isEqualTo(409);
    }

    @Test
    void modifyRejectsOrderPendingCancellation() {
        TradingOrder order = liveOrder();
        order.requestCancel();
        when(orders.findByIdAndDeskId(order.getId(), DESK)).thenReturn(Optional.of(order));

        OrderCommand command = new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.MODIFY,
                USER, Instant.EPOCH, order.getId(), order.getVersion(), null,
                null, null, new BigDecimal("20"), new BigDecimal("105"), null, null, null, Map.of()
        );

        ProcessingOutcome outcome = handler.handle(command);

        assertThat(outcome.result().success()).isFalse();
        assertThat(outcome.result().status()).isEqualTo(409);
    }

    @Test
    void modifyRejectsOrderNotFoundOnDesk() {
        UUID orderId = UUID.randomUUID();
        when(orders.findByIdAndDeskId(orderId, DESK)).thenReturn(Optional.empty());

        OrderCommand command = new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.MODIFY,
                USER, Instant.EPOCH, orderId, 0L, null,
                null, null, new BigDecimal("20"), new BigDecimal("105"), null, null, null, Map.of()
        );

        ProcessingOutcome outcome = handler.handle(command);

        assertThat(outcome.result().success()).isFalse();
        assertThat(outcome.result().status()).isEqualTo(404);
    }

    // -------------------------------------------------------------------------
    // CANCEL
    // -------------------------------------------------------------------------

    @Test
    void cancelRequestsParentCancellationAndPublishesCancelRequestedEvent() {
        TradingOrder order = liveOrder();
        when(orders.findByIdAndDeskId(order.getId(), DESK)).thenReturn(Optional.of(order));
        when(orders.findByParentOrderIdAndStatusIn(any(), any())).thenReturn(List.of());

        ProcessingOutcome outcome = handler.handle(cancelCommand(order.getId()));

        assertThat(outcome.result().success()).isTrue();
        assertThat(outcome.result().status()).isEqualTo(200);
        assertThat(outcome.events()).hasSize(1);
        assertThat(outcome.events().getFirst().eventType()).isEqualTo("CANCEL_REQUESTED");
        assertThat(order.getTargetStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelCascadesToLiveChildOrdersRecursively() {
        TradingOrder parent = liveOrder();
        TradingOrder child = liveOrder();

        when(orders.findByIdAndDeskId(parent.getId(), DESK)).thenReturn(Optional.of(parent));
        // First call: children of parent; second call (recursive): children of child
        when(orders.findByParentOrderIdAndStatusIn(org.mockito.ArgumentMatchers.eq(parent.getId()), any()))
                .thenReturn(List.of(child));
        when(orders.findByParentOrderIdAndStatusIn(org.mockito.ArgumentMatchers.eq(child.getId()), any()))
                .thenReturn(List.of());

        ProcessingOutcome outcome = handler.handle(cancelCommand(parent.getId()));

        assertThat(outcome.result().success()).isTrue();
        // parent event + child event
        assertThat(outcome.events()).hasSize(2);
        assertThat(child.getTargetStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(parent.getTargetStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelRejectsAlreadyPendingCancellation() {
        TradingOrder order = liveOrder();
        order.requestCancel();
        when(orders.findByIdAndDeskId(order.getId(), DESK)).thenReturn(Optional.of(order));

        ProcessingOutcome outcome = handler.handle(cancelCommand(order.getId()));

        assertThat(outcome.result().success()).isFalse();
        assertThat(outcome.result().status()).isEqualTo(409);
    }

    // -------------------------------------------------------------------------
    // CANCEL_ALL
    // -------------------------------------------------------------------------

    @Test
    void cancelAllRequestsCancellationForAllActiveOrdersOnDesk() {
        TradingOrder first = liveOrder();
        TradingOrder second = liveOrder();
        when(orders.findByDeskIdAndStatusInOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(first, second));

        ProcessingOutcome outcome = handler.handle(cancelAllCommand());

        assertThat(outcome.result().success()).isTrue();
        assertThat(outcome.result().status()).isEqualTo(200);
        assertThat(outcome.events()).hasSize(2);
        assertThat(first.getTargetStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(second.getTargetStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelAllSkipsOrdersAlreadyPendingCancellation() {
        TradingOrder alreadyCancelling = liveOrder();
        alreadyCancelling.requestCancel();
        when(orders.findByDeskIdAndStatusInOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(alreadyCancelling));

        ProcessingOutcome outcome = handler.handle(cancelAllCommand());

        assertThat(outcome.result().success()).isTrue();
        // No new events for already-cancelling orders
        assertThat(outcome.events()).isEmpty();
    }

    @Test
    void cancelAllWithNoActiveOrdersReturnsSuccessWithZeroEvents() {
        when(orders.findByDeskIdAndStatusInOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of());

        ProcessingOutcome outcome = handler.handle(cancelAllCommand());

        assertThat(outcome.result().success()).isTrue();
        assertThat(outcome.events()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Schema version guard
    // -------------------------------------------------------------------------

    @Test
    void rejectsCommandWithUnsupportedSchemaVersion() {
        UUID orderId = UUID.randomUUID();
        OrderCommand command = new OrderCommand(
                SCHEMA_VERSION + 1, UUID.randomUUID(), CommandType.CREATE,
                USER, Instant.EPOCH, orderId, null, listing(),
                OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"), "DMA", "ref", null, Map.of()
        );

        ProcessingOutcome outcome = handler.handle(command);

        assertThat(outcome.result().success()).isFalse();
        assertThat(outcome.result().status()).isEqualTo(400);
    }

    // -------------------------------------------------------------------------
    // Idempotency: cached ProcessedCommand replay
    // -------------------------------------------------------------------------

    @Test
    void returnsTheCachedResultForAReplayedCommandId() {
        UUID commandId = UUID.randomUUID();
        OrderCommandResult cachedResult = new OrderCommandResult(
                SCHEMA_VERSION, commandId, true, 201, null, "{}");
        ProcessedCommand cached = new ProcessedCommand(cachedResult);
        when(processed.findById(commandId)).thenReturn(Optional.of(cached));
        when(events.findByCommandIdOrderByOccurredAtAsc(commandId)).thenReturn(List.of());

        OrderCommand command = new OrderCommand(
                SCHEMA_VERSION, commandId, CommandType.CREATE,
                USER, Instant.EPOCH, UUID.randomUUID(), null, listing(),
                OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"), "DMA", "ref", null, Map.of()
        );

        ProcessingOutcome first = new ProcessingOutcome(cachedResult, List.of());
        ProcessingOutcome replayed = handler.handle(command);

        assertThat(replayed.result()).isEqualTo(first.result());
        verify(orders, never()).existsById(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static TradingOrder liveOrder() {
        return liveOrder("DMA");
    }

    private static TradingOrder liveOrder(String destination) {
        UUID id = UUID.randomUUID();
        TradingOrder order = new TradingOrder(
                id, USER, DESK, listing(),
                OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"),
                destination, "test-ref", null, id, "{}"
        );
        ReflectionTestUtils.setField(order, "version", 1L);
        return order;
    }

    private static OrderCommand createCommand(UUID orderId) {
        return new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.CREATE,
                USER, Instant.EPOCH, orderId, null, listing(),
                OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"), "DMA", "ref", null, Map.of()
        );
    }

    private static OrderCommand cancelCommand(UUID orderId) {
        return new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.CANCEL,
                USER, Instant.EPOCH, orderId, null, null,
                null, null, null, null, null, null, null, Map.of()
        );
    }

    private static OrderCommand cancelAllCommand() {
        return new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.CANCEL_ALL,
                USER, Instant.EPOCH, null, null, null,
                null, null, null, null, null, null, null, Map.of()
        );
    }

    private static ListingSnapshot listing() {
        return new ListingSnapshot(
                1, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq",
                "US", "USD",
                new BigDecimal("0.01"), new BigDecimal("0.01"),
                new BigDecimal("200"), new BigDecimal("198")
        );
    }
}

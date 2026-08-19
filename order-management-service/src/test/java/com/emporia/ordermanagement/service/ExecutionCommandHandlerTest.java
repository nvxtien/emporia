package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.ExecutionCommand;
import com.emporia.events.TradingEvents.ExecutionCommandType;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.execution.ShardedOrderDispatcher;
import com.emporia.ordermanagement.model.Execution;
import com.emporia.ordermanagement.model.OrderEvent;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.ExecutionRepository;
import com.emporia.ordermanagement.repository.OrderEventRepository;
import com.emporia.ordermanagement.repository.ProcessedCommandRepository;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionCommandHandlerTest {
    private final TradingOrderRepository orders = mock(TradingOrderRepository.class);
    private final ExecutionRepository executions = mock(ExecutionRepository.class);
    private final OrderEventRepository events = mock(OrderEventRepository.class);
    private final ProcessedCommandRepository processed = mock(ProcessedCommandRepository.class);
    private final OrderMetrics metrics = new OrderMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    private final OrderStateCache cache = new OrderStateCache(orders, processed, metrics, null, 1000, 1000);
    private final AsyncDbWriter asyncDbWriter = mock(AsyncDbWriter.class);
    private final ShardedOrderDispatcher dispatcher = mock(ShardedOrderDispatcher.class);
    private final ExecutionCommandHandler handler =
            new ExecutionCommandHandler(orders, executions, new ObjectMapper(), metrics, cache, asyncDbWriter, dispatcher);

    @Test
    void recordsAPartialFillAndPublishesTheNewOrderState() {
        TradingOrder order = order();
        when(executions.existsByExecutionReference("venue-fill-1")).thenReturn(false);
        when(orders.findByIdAndDeskId(order.getId(), "desk-a")).thenReturn(Optional.of(order));
        when(events.save(any(OrderEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var event = handler.handle(command(
                order, ExecutionCommandType.FILL, "venue-fill-1",
                new BigDecimal("4"), new BigDecimal("101.25"), null
        )).getFirst();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(order.getTradedQuantity()).isEqualByComparingTo("4");
        assertThat(order.getRemainingQuantity()).isEqualByComparingTo("6");
        assertThat(order.getAverageTradePrice()).isEqualByComparingTo("101.250000");
        assertThat(event.eventType()).isEqualTo("PARTIALLY_FILLED");
        assertThat(event.deskId()).isEqualTo("desk-a");

        ArgumentCaptor<Execution> persisted = ArgumentCaptor.forClass(Execution.class);
        verify(asyncDbWriter).enqueue(persisted.capture());
        assertThat(persisted.getValue().getExecutionReference()).isEqualTo("venue-fill-1");
        assertThat(persisted.getValue().getVenue()).isEqualTo("XNAS");
        verify(asyncDbWriter).enqueue(order);
        verify(dispatcher, times(1)).dispatch(any(OrderDomainEvent.class));
    }

    @Test
    void rejectsAnUnfilledLiveOrder() {
        TradingOrder order = order();
        when(executions.existsByExecutionReference("venue-reject-1")).thenReturn(false);
        when(orders.findByIdAndDeskId(order.getId(), "desk-a")).thenReturn(Optional.of(order));
        when(events.save(any(OrderEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var event = handler.handle(command(
                order, ExecutionCommandType.REJECT, "venue-reject-1", null, null, "Venue is closed"
        )).getFirst();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getErrorMessage()).isEqualTo("Venue is closed");
        assertThat(event.eventType()).isEqualTo("REJECTED");
        verify(asyncDbWriter, never()).enqueue(any(Execution.class));
        verify(dispatcher, times(1)).dispatch(any(OrderDomainEvent.class));
    }

    @Test
    void ignoresDuplicateReferencesButAcceptsAFillReportedAfterCancellation() {
        TradingOrder order = order();
        when(executions.existsByExecutionReference("duplicate")).thenReturn(true);

        assertThat(handler.handle(command(
                order, ExecutionCommandType.FILL, "duplicate", BigDecimal.ONE, BigDecimal.TEN, null
        ))).isEmpty();
        verify(orders, never()).findByIdAndDeskId(any(), any());

        when(executions.existsByExecutionReference("late-fill")).thenReturn(false);
        when(orders.findByIdAndDeskId(order.getId(), "desk-a")).thenReturn(Optional.of(order));
        when(events.save(any(OrderEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        order.cancel();

        var lateEvents = handler.handle(command(
                order, ExecutionCommandType.FILL, "late-fill", BigDecimal.ONE, BigDecimal.TEN, null
        ));

        assertThat(lateEvents).hasSize(1);
        assertThat(lateEvents.getFirst().eventType()).isEqualTo("CANCELLED_FILL");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getTradedQuantity()).isEqualByComparingTo("1");
        verify(asyncDbWriter).enqueue(order);
        // The duplicate reference produced no event and no outbox row; only the late fill did.
        verify(dispatcher, times(1)).dispatch(any(OrderDomainEvent.class));
    }

    @Test
    void cannotApplyAnExecutionFromAnotherDesk() {
        TradingOrder order = order();
        when(executions.existsByExecutionReference("wrong-desk")).thenReturn(false);
        when(orders.findByIdAndDeskId(order.getId(), "desk-a")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(command(
                order, ExecutionCommandType.FILL, "wrong-desk", BigDecimal.ONE, BigDecimal.TEN, null
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found on its desk");
    }

    @Test
    void rollsEveryPartialChildExecutionUpToItsParentInTheSameCall() {
        TradingOrder parent = order();
        TradingOrder child = child(parent);
        when(executions.existsByExecutionReference(any())).thenReturn(false);
        when(orders.findByIdAndDeskId(child.getId(), "desk-a")).thenReturn(Optional.of(child));
        when(orders.findByIdAndDeskId(parent.getId(), "desk-a")).thenReturn(Optional.of(parent));
        when(events.save(any(OrderEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var published = handler.handle(command(
                child, ExecutionCommandType.FILL, "child-partial-1",
                new BigDecimal("4"), new BigDecimal("101.25"), null
        ));

        assertThat(published).extracting(event -> event.orderId())
                .containsExactly(child.getId(), parent.getId());
        assertThat(child.getTradedQuantity()).isEqualByComparingTo("4");
        assertThat(parent.getTradedQuantity()).isEqualByComparingTo("4");
        assertThat(parent.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        ArgumentCaptor<Execution> persisted = ArgumentCaptor.forClass(Execution.class);
        verify(asyncDbWriter, org.mockito.Mockito.times(2)).enqueue(persisted.capture());
        assertThat(persisted.getAllValues()).extracting(Execution::getOrder)
                .containsExactly(child, parent);
        verify(dispatcher, times(2)).dispatch(any(OrderDomainEvent.class));
    }

    @Test
    void fillCanWinWhileCancellationIsPendingAndCancelAcknowledgesTheRemainder() {
        TradingOrder order = order();
        order.requestCancel();
        when(executions.existsByExecutionReference("race-fill")).thenReturn(false);
        when(orders.findByIdAndDeskId(order.getId(), "desk-a")).thenReturn(Optional.of(order));
        when(events.save(any(OrderEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        handler.handle(command(order, ExecutionCommandType.FILL, "race-fill",
                new BigDecimal("4"), new BigDecimal("101.25"), null));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(order.getTargetStatus()).isEqualTo(OrderStatus.CANCELLED);

        var cancelled = handler.handle(command(order, ExecutionCommandType.CANCEL, "race-cancel",
                null, null, "Venue cancel acknowledgement"));

        assertThat(cancelled).hasSize(1);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getTradedQuantity()).isEqualByComparingTo("4");
        assertThat(order.getRemainingQuantity()).isEqualByComparingTo("6");
        // One outbox row for the partial fill, one for the cancel confirmation.
        verify(dispatcher, times(2)).dispatch(any(OrderDomainEvent.class));
    }

    @Test
    void rejectsInvalidSchemaVersion() {
        TradingOrder order = order();
        ExecutionCommand invalidVersionCommand = new ExecutionCommand(
                SCHEMA_VERSION + 1, UUID.randomUUID(), ExecutionCommandType.FILL,
                order.getId(), "desk-a", "ref-invalid",
                new BigDecimal("1"), new BigDecimal("100"), "XNAS", Instant.now(), null
        );

        assertThatThrownBy(() -> handler.handle(invalidVersionCommand))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported execution command schema version");
    }

    @Test
    void ignoreOpsOnAlreadyTerminalOrder() {
        TradingOrder order = order();
        order.reject("Already rejected");
        when(orders.findByIdAndDeskId(order.getId(), "desk-a")).thenReturn(Optional.of(order));

        // FILL on rejected order returns empty
        var fillResult = handler.handle(command(order, ExecutionCommandType.FILL, "fill-term", BigDecimal.ONE, BigDecimal.TEN, null));
        assertThat(fillResult).isEmpty();

        // REJECT on terminal order returns empty
        var rejectResult = handler.handle(command(order, ExecutionCommandType.REJECT, "rej-term", null, null, "venue error"));
        assertThat(rejectResult).isEmpty();

        // CANCEL on terminal order returns empty
        var cancelResult = handler.handle(command(order, ExecutionCommandType.CANCEL, "cnl-term", null, null, "venue cancel"));
        assertThat(cancelResult).isEmpty();

        verify(dispatcher, never()).dispatch(any(OrderDomainEvent.class));
    }

    @Test
    void deferredStrategyParentCancelWhenChildrenAreActive() {
        TradingOrder parent = new TradingOrder(
                UUID.randomUUID(), "trader-a", "desk-a", listing(), OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("102"), "SMART", "strategy-order",
                null, null, "{}"
        );
        ReflectionTestUtils.setField(parent, "version", 0L);
        TradingOrder child = child(parent);

        when(orders.findByIdAndDeskId(parent.getId(), "desk-a")).thenReturn(Optional.of(parent));
        when(orders.findByParentOrderIdAndStatusIn(org.mockito.ArgumentMatchers.eq(parent.getId()), any()))
                .thenReturn(List.of(child));

        // Confirming cancel on strategy parent with active children returns empty (deferred)
        var result = handler.handle(command(parent, ExecutionCommandType.CANCEL, "strategy-cancel-1", null, null, null));
        assertThat(result).isEmpty();
        assertThat(parent.getStatus()).isEqualTo(OrderStatus.LIVE);
        verify(dispatcher, never()).dispatch(any(OrderDomainEvent.class));
    }

    private static TradingOrder order() {
        TradingOrder order = new TradingOrder(
                UUID.randomUUID(), "trader-a", "desk-a", listing(), OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("102"), "DMA", "test-order",
                null, null, "{}"
        );
        ReflectionTestUtils.setField(order, "version", 0L);
        return order;
    }

    private static TradingOrder child(TradingOrder parent) {
        TradingOrder child = new TradingOrder(
                UUID.randomUUID(), "trader-a", "desk-a", listing(), OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("102"), "DMA", parent.getId().toString(),
                parent.getId(), parent.getRootOrderId(), "{}"
        );
        ReflectionTestUtils.setField(child, "version", 0L);
        return child;
    }

    private static ExecutionCommand command(TradingOrder order, ExecutionCommandType type, String reference,
                                            BigDecimal quantity, BigDecimal price, String detail) {
        return new ExecutionCommand(
                SCHEMA_VERSION, UUID.randomUUID(), type, order.getId(), "desk-a", reference,
                quantity, price, "XNAS", Instant.parse("2026-07-26T00:00:00Z"), detail
        );
    }

    private static ListingSnapshot listing() {
        return new ListingSnapshot(
                7, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq",
                "US", "USD", new BigDecimal("0.01"), BigDecimal.ONE,
                new BigDecimal("101"), new BigDecimal("100")
        );
    }
}

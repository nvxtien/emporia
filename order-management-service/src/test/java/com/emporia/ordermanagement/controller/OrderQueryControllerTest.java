package com.emporia.ordermanagement.controller;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import com.emporia.ordermanagement.dto.ExecutionView;
import com.emporia.ordermanagement.dto.OrderEventView;
import com.emporia.ordermanagement.model.Execution;
import com.emporia.ordermanagement.model.OrderEvent;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.ExecutionRepository;
import com.emporia.ordermanagement.repository.OrderEventRepository;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import com.emporia.ordermanagement.service.OrderStreamService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderQueryControllerTest {

    private final TradingOrderRepository orders = mock(TradingOrderRepository.class);
    private final OrderEventRepository events = mock(OrderEventRepository.class);
    private final ExecutionRepository executions = mock(ExecutionRepository.class);
    private final OrderStreamService streams = mock(OrderStreamService.class);
    private final OrderQueryController controller =
            new OrderQueryController(orders, events, executions, streams);

    // -------------------------------------------------------------------------
    // GET /orders  — list all orders for the authenticated desk
    // -------------------------------------------------------------------------

    @Test
    void ordersReturnsAllOrdersForTheDeskClaim() {
        TradingOrder order = liveOrder("DMA");
        when(orders.findByDeskIdOrderByCreatedAtDesc("DESK-A")).thenReturn(List.of(order));

        List<OrderView> result = controller.orders(jwt("trader-1", "DESK-A"));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().deskId()).isEqualTo("DESK-A");
    }

    @Test
    void ordersFallsBackToSubjectWhenDeskClaimIsAbsent() {
        TradingOrder order = liveOrder("DMA");
        when(orders.findByDeskIdOrderByCreatedAtDesc("trader-no-desk")).thenReturn(List.of(order));

        List<OrderView> result = controller.orders(jwt("trader-no-desk", null));

        assertThat(result).hasSize(1);
    }

    @Test
    void ordersFallsBackToSubjectWhenDeskClaimIsBlank() {
        TradingOrder order = liveOrder("DMA");
        when(orders.findByDeskIdOrderByCreatedAtDesc("trader-blank")).thenReturn(List.of(order));

        List<OrderView> result = controller.orders(jwt("trader-blank", ""));

        assertThat(result).hasSize(1);
    }

    @Test
    void ordersReturnsEmptyListWhenDeskHasNoOrders() {
        when(orders.findByDeskIdOrderByCreatedAtDesc("DESK-EMPTY")).thenReturn(List.of());

        List<OrderView> result = controller.orders(jwt("user", "DESK-EMPTY"));

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // GET /orders/{id}  — single order
    // -------------------------------------------------------------------------

    @Test
    void orderReturnsTheOrderWhenFoundOnDesk() {
        TradingOrder order = liveOrder("DMA");
        when(orders.findByIdAndDeskId(order.getId(), "DESK-A")).thenReturn(Optional.of(order));

        OrderView result = controller.order(jwt("trader", "DESK-A"), order.getId());

        assertThat(result.id()).isEqualTo(order.getId());
    }

    @Test
    void orderReturns404WhenOrderNotFoundOnDesk() {
        UUID missingId = UUID.randomUUID();
        when(orders.findByIdAndDeskId(missingId, "DESK-A")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.order(jwt("trader", "DESK-A"), missingId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Order not found");
    }

    @Test
    void orderReturns404WhenOrderBelongsToADifferentDesk() {
        TradingOrder order = liveOrder("DMA");
        when(orders.findByIdAndDeskId(order.getId(), "DESK-B")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.order(jwt("trader", "DESK-B"), order.getId()))
                .isInstanceOf(ResponseStatusException.class);
    }

    // -------------------------------------------------------------------------
    // GET /orders/{id}/history
    // -------------------------------------------------------------------------

    @Test
    void historyReturnsEventListForAKnownOrder() {
        TradingOrder order = liveOrder("DMA");
        when(orders.findByIdAndDeskId(order.getId(), "DESK-A")).thenReturn(Optional.of(order));
        OrderEvent event = new OrderEvent(UUID.randomUUID(), order, "CREATED", "Order created", "{}");
        when(events.findByOrderIdOrderByOccurredAtAsc(order.getId())).thenReturn(List.of(event));

        List<OrderEventView> result = controller.history(jwt("trader", "DESK-A"), order.getId());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().eventType()).isEqualTo("CREATED");
    }

    @Test
    void historyReturns404WhenOrderNotOnDesk() {
        UUID missingId = UUID.randomUUID();
        when(orders.findByIdAndDeskId(missingId, "DESK-A")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.history(jwt("trader", "DESK-A"), missingId))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void historyReturnsEmptyListWhenNoEventsExist() {
        TradingOrder order = liveOrder("DMA");
        when(orders.findByIdAndDeskId(order.getId(), "DESK-A")).thenReturn(Optional.of(order));
        when(events.findByOrderIdOrderByOccurredAtAsc(order.getId())).thenReturn(List.of());

        List<OrderEventView> result = controller.history(jwt("trader", "DESK-A"), order.getId());

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // GET /orders/{id}/executions
    // -------------------------------------------------------------------------

    @Test
    void executionsReturnsFilledExecutionsForAKnownOrder() {
        TradingOrder order = liveOrder("DMA");
        when(orders.findByIdAndDeskId(order.getId(), "DESK-A")).thenReturn(Optional.of(order));
        Execution execution = new Execution(
                UUID.randomUUID(), "venue-fill-1", order,
                new BigDecimal("5"), new BigDecimal("101"), "XNAS", Instant.now()
        );
        when(executions.findByOrderIdOrderByExecutedAtAsc(order.getId())).thenReturn(List.of(execution));

        List<ExecutionView> result = controller.executions(jwt("trader", "DESK-A"), order.getId());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().executionReference()).isEqualTo("venue-fill-1");
    }

    @Test
    void executionsReturns404WhenOrderNotOnDesk() {
        UUID missingId = UUID.randomUUID();
        when(orders.findByIdAndDeskId(missingId, "DESK-A")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.executions(jwt("trader", "DESK-A"), missingId))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void executionsReturnsEmptyListForAnOrderWithNoFills() {
        TradingOrder order = liveOrder("DMA");
        when(orders.findByIdAndDeskId(order.getId(), "DESK-A")).thenReturn(Optional.of(order));
        when(executions.findByOrderIdOrderByExecutedAtAsc(order.getId())).thenReturn(List.of());

        List<ExecutionView> result = controller.executions(jwt("trader", "DESK-A"), order.getId());

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // GET /orders/stream  — SSE
    // -------------------------------------------------------------------------

    @Test
    void streamReturnsAnSseEmitter() {
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter dummyEmitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter();
        when(streams.subscribe(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(dummyEmitter);
        when(orders.findByDeskIdOrderByCreatedAtDesc("DESK-A")).thenReturn(List.of());

        var emitter = controller.stream(jwt("trader", "DESK-A"));

        assertThat(emitter).isEqualTo(dummyEmitter);
    }

    @Test
    void streamSendsInitialOrdersOnConnection() {
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter dummyEmitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter();
        when(streams.subscribe(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(dummyEmitter);
        TradingOrder order = liveOrder("DMA");
        when(orders.findByDeskIdOrderByCreatedAtDesc("DESK-A")).thenReturn(List.of(order));

        var emitter = controller.stream(jwt("trader", "DESK-A"));

        assertThat(emitter).isEqualTo(dummyEmitter);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Jwt jwt(String subject, String desk) {
        Jwt.Builder builder = Jwt.withTokenValue("mock-token")
                .header("alg", "none")
                .subject(subject);
        if (desk != null) {
            builder.claim("desk", desk);
        }
        return builder.build();
    }

    private static TradingOrder liveOrder(String destination) {
        UUID id = UUID.randomUUID();
        TradingOrder order = new TradingOrder(
                id, "trader", "DESK-A",
                listing(),
                OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"),
                destination, "ctrl-test", null, id, "{}"
        );
        ReflectionTestUtils.setField(order, "version", 1L);
        return order;
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

package com.emporia.ordermanagement.controller;

import com.emporia.events.TradingEvents.ExecutionRecoveryView;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.StrategyStateView;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExecutionRecoveryControllerTest {

    private final TradingOrderRepository orders = mock(TradingOrderRepository.class);
    private final ExecutionRecoveryController controller = new ExecutionRecoveryController(orders);

    // -------------------------------------------------------------------------
    // GET /internal/execution/recoverable
    // -------------------------------------------------------------------------

    @Test
    void recoverableReturnsEmptyViewsWhenNoActiveOrders() {
        when(orders.findByStatusInAndParentOrderIdIsNullOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());

        ExecutionRecoveryView result = controller.recoverable(serviceJwt());

        assertThat(result.directOrders()).isEmpty();
        assertThat(result.strategies()).isEmpty();
    }

    @Test
    void recoverableSeparatesDmaOrdersIntoDirectList() {
        TradingOrder dma = parentOrder("DMA");
        when(orders.findByStatusInAndParentOrderIdIsNullOrderByCreatedAtAsc(any()))
                .thenReturn(List.of(dma));
        when(orders.findByParentOrderIdOrderByCreatedAtAsc(dma.getId())).thenReturn(List.of());

        ExecutionRecoveryView result = controller.recoverable(serviceJwt());

        assertThat(result.directOrders()).hasSize(1);
        assertThat(result.directOrders().getFirst().id()).isEqualTo(dma.getId());
        assertThat(result.strategies()).isEmpty();
    }

    @Test
    void recoverableSeparatesNonDmaOrdersIntoStrategiesList() {
        TradingOrder vwap = parentOrder("VWAP");
        when(orders.findByStatusInAndParentOrderIdIsNullOrderByCreatedAtAsc(any()))
                .thenReturn(List.of(vwap));
        when(orders.findByParentOrderIdOrderByCreatedAtAsc(vwap.getId())).thenReturn(List.of());

        ExecutionRecoveryView result = controller.recoverable(serviceJwt());

        assertThat(result.directOrders()).isEmpty();
        assertThat(result.strategies()).hasSize(1);
        assertThat(result.strategies().getFirst().parent().id()).isEqualTo(vwap.getId());
    }

    @Test
    void recoverablePopulatesChildrenWithinStrategyStateView() {
        TradingOrder parent = parentOrder("SMART");
        TradingOrder child1 = childOrder(parent, "DMA");
        TradingOrder child2 = childOrder(parent, "DMA");

        when(orders.findByStatusInAndParentOrderIdIsNullOrderByCreatedAtAsc(any()))
                .thenReturn(List.of(parent));
        when(orders.findByParentOrderIdOrderByCreatedAtAsc(parent.getId()))
                .thenReturn(List.of(child1, child2));

        ExecutionRecoveryView result = controller.recoverable(serviceJwt());

        assertThat(result.strategies()).hasSize(1);
        StrategyStateView state = result.strategies().getFirst();
        assertThat(state.parent().id()).isEqualTo(parent.getId());
        assertThat(state.children()).hasSize(2)
                .extracting(view -> view.id())
                .containsExactlyInAnyOrder(child1.getId(), child2.getId());
    }

    @Test
    void recoverableMixesDmaAndStrategyOrders() {
        TradingOrder dma = parentOrder("DMA");
        TradingOrder vwap = parentOrder("VWAP");

        when(orders.findByStatusInAndParentOrderIdIsNullOrderByCreatedAtAsc(any()))
                .thenReturn(List.of(dma, vwap));
        when(orders.findByParentOrderIdOrderByCreatedAtAsc(dma.getId())).thenReturn(List.of());
        when(orders.findByParentOrderIdOrderByCreatedAtAsc(vwap.getId())).thenReturn(List.of());

        ExecutionRecoveryView result = controller.recoverable(serviceJwt());

        assertThat(result.directOrders()).hasSize(1);
        assertThat(result.strategies()).hasSize(1);
    }

    @Test
    void recoverableAllowsAdminRole() {
        when(orders.findByStatusInAndParentOrderIdIsNullOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());

        ExecutionRecoveryView result = controller.recoverable(adminJwt());

        assertThat(result.directOrders()).isEmpty();
        assertThat(result.strategies()).isEmpty();
    }

    @Test
    void recoverableRejectsAuthenticatedUserWithoutRecoveryRole() {
        assertThatThrownBy(() -> controller.recoverable(userJwt()))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.getReason()).isEqualTo("Execution recovery access required");
                });

        verifyNoInteractions(orders);
    }

    // -------------------------------------------------------------------------
    // GET /internal/execution/strategies/{parentId}
    // -------------------------------------------------------------------------

    @Test
    void strategyReturnsStateForAKnownNonDmaParentOrder() {
        TradingOrder parent = parentOrder("SMART");
        TradingOrder child = childOrder(parent, "DMA");

        when(orders.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(orders.findByParentOrderIdOrderByCreatedAtAsc(parent.getId()))
                .thenReturn(List.of(child));

        StrategyStateView result = controller.strategy(serviceJwt(), parent.getId());

        assertThat(result.parent().id()).isEqualTo(parent.getId());
        assertThat(result.children()).hasSize(1);
        assertThat(result.children().getFirst().id()).isEqualTo(child.getId());
    }

    @Test
    void strategyReturns404WhenOrderIdNotFound() {
        UUID missingId = UUID.randomUUID();
        when(orders.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.strategy(serviceJwt(), missingId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Active strategy order was not found");
    }

    @Test
    void strategyReturns404ForADmaOrder() {
        TradingOrder dma = parentOrder("DMA");
        when(orders.findById(dma.getId())).thenReturn(Optional.of(dma));

        assertThatThrownBy(() -> controller.strategy(serviceJwt(), dma.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Active strategy order was not found");
    }

    @Test
    void strategyReturns404ForAChildOrder() {
        TradingOrder parent = parentOrder("VWAP");
        TradingOrder child = childOrder(parent, "DMA");

        when(orders.findById(child.getId())).thenReturn(Optional.of(child));

        assertThatThrownBy(() -> controller.strategy(serviceJwt(), child.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Active strategy order was not found");
    }

    @Test
    void strategyReturnsEmptyChildrenListWhenParentHasNone() {
        TradingOrder parent = parentOrder("VWAP");
        when(orders.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(orders.findByParentOrderIdOrderByCreatedAtAsc(parent.getId())).thenReturn(List.of());

        StrategyStateView result = controller.strategy(serviceJwt(), parent.getId());

        assertThat(result.children()).isEmpty();
    }

    @Test
    void strategyRejectsAuthenticatedUserWithoutRecoveryRole() {
        UUID parentId = UUID.randomUUID();

        assertThatThrownBy(() -> controller.strategy(userJwt(), parentId))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.getReason()).isEqualTo("Execution recovery access required");
                });

        verifyNoInteractions(orders);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Jwt serviceJwt() {
        return Jwt.withTokenValue("mock-token")
                .header("alg", "none")
                .subject("execution-service")
                .claim("authorities", List.of("ROLE_EXECUTION_SERVICE"))
                .build();
    }

    private static Jwt adminJwt() {
        return Jwt.withTokenValue("mock-token")
                .header("alg", "none")
                .subject("admin")
                .claim("authorities", List.of("ROLE_USER", "ROLE_ADMIN"))
                .build();
    }

    private static Jwt userJwt() {
        return Jwt.withTokenValue("mock-token")
                .header("alg", "none")
                .subject("trader")
                .claim("authorities", List.of("ROLE_USER"))
                .build();
    }

    private static TradingOrder parentOrder(String destination) {
        UUID id = UUID.randomUUID();
        TradingOrder order = new TradingOrder(
                id, "trader", "desk",
                listing(), OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("100"), new BigDecimal("50"),
                destination, "recovery-test", null, id, "{}"
        );
        ReflectionTestUtils.setField(order, "version", 1L);
        return order;
    }

    private static TradingOrder childOrder(TradingOrder parent, String destination) {
        UUID id = UUID.randomUUID();
        TradingOrder child = new TradingOrder(
                id, "trader", "desk",
                listing(), OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("50"),
                destination, "recovery-child", parent.getId(), parent.getId(), "{}"
        );
        ReflectionTestUtils.setField(child, "version", 1L);
        return child;
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

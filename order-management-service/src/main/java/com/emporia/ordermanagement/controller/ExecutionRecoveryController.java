package com.emporia.ordermanagement.controller;

import com.emporia.events.TradingEvents.ExecutionRecoveryView;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderView;
import com.emporia.events.TradingEvents.StrategyStateView;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Authenticated service-to-service projection used to rebuild execution state.
 * It is intentionally not routed by the browser gateway.
 */
@RestController
@RequestMapping("/internal/execution")
class ExecutionRecoveryController {
    private static final List<OrderStatus> ACTIVE =
            List.of(OrderStatus.LIVE, OrderStatus.PARTIALLY_FILLED);

    private final TradingOrderRepository orders;

    ExecutionRecoveryController(TradingOrderRepository orders) {
        this.orders = orders;
    }

    @GetMapping("/recoverable")
    @Transactional(readOnly = true)
    ExecutionRecoveryView recoverable() {
        List<TradingOrder> parents =
                orders.findByStatusInAndParentOrderIdIsNullOrderByCreatedAtAsc(ACTIVE);
        List<OrderView> direct = parents.stream()
                .filter(order -> "DMA".equalsIgnoreCase(order.getDestination()))
                .map(TradingOrder::view)
                .toList();
        List<StrategyStateView> strategies = parents.stream()
                .filter(order -> !"DMA".equalsIgnoreCase(order.getDestination()))
                .map(this::state)
                .toList();
        return new ExecutionRecoveryView(direct, strategies);
    }

    @GetMapping("/strategies/{parentId}")
    @Transactional(readOnly = true)
    StrategyStateView strategy(@PathVariable UUID parentId) {
        TradingOrder parent = orders.findById(parentId)
                .filter(order -> order.getParentOrderId() == null)
                .filter(order -> !"DMA".equalsIgnoreCase(order.getDestination()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Active strategy order was not found"));
        return state(parent);
    }

    private StrategyStateView state(TradingOrder parent) {
        return new StrategyStateView(
                parent.view(),
                orders.findByParentOrderIdOrderByCreatedAtAsc(parent.getId()).stream()
                        .map(TradingOrder::view)
                        .toList()
        );
    }
}

package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class OrderMetrics {
    OrderMetrics(MeterRegistry meters, TradingOrderRepository orders) {
        Gauge.builder("total_orders", orders, TradingOrderRepository::count)
                .description("The total number of orders").register(meters);
        Gauge.builder("live_orders", orders,
                        repository -> repository.countByStatusIn(List.of(OrderStatus.LIVE, OrderStatus.PARTIALLY_FILLED)))
                .description("The number of active orders").register(meters);
        Gauge.builder("cancelled_orders", orders, repository -> repository.countByStatus(OrderStatus.CANCELLED))
                .description("The number of cancelled orders").register(meters);
        Gauge.builder("filled_orders", orders, repository -> repository.countByStatus(OrderStatus.FILLED))
                .description("The number of filled orders").register(meters);
        Gauge.builder("rejected_orders", orders, repository -> repository.countByStatus(OrderStatus.REJECTED))
                .description("The number of rejected orders").register(meters);
        Gauge.builder("none_status_orders", () -> 0)
                .description("Compatibility gauge; Emporia orders always have a persisted lifecycle status").register(meters);
        Gauge.builder("pending_live_orders", () -> 0)
                .description("Compatibility gauge; Emporia persists accepted orders directly as live").register(meters);
        Gauge.builder("pending_cancel_orders", orders,
                        repository -> repository.countByTargetStatusAndStatusIn(
                                OrderStatus.CANCELLED,
                                List.of(OrderStatus.LIVE, OrderStatus.PARTIALLY_FILLED)))
                .description("The number of orders awaiting venue cancellation confirmation").register(meters);
    }
}

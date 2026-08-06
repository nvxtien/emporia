package com.emporia.ordermanagement.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Maintains in-process order status counters and Micrometer Counters so Prometheus
 * scrapes do not execute {@code COUNT(*)} queries against PostgreSQL on every tick.
 *
 * <p>Each state transition increments Micrometer Counters and updates in-memory
 * {@link AtomicLong} gauges without touching PostgreSQL.
 */
@Component
public class OrderMetrics {
    final AtomicLong liveOrders = new AtomicLong();
    final AtomicLong cancelledOrders = new AtomicLong();
    final AtomicLong filledOrders = new AtomicLong();
    final AtomicLong rejectedOrders = new AtomicLong();
    final AtomicLong totalOrders = new AtomicLong();
    final AtomicLong pendingCancelOrders = new AtomicLong();

    private final Counter createdCounter;
    private final Counter filledCounter;
    private final Counter cancelledCounter;
    private final Counter rejectedCounter;

    public OrderMetrics(MeterRegistry meters) {
        // Micrometer Counters incremented on each state transition instead of querying DB
        createdCounter = Counter.builder("emporia.orders.created.total")
                .description("The total number of created orders")
                .register(meters);
        filledCounter = Counter.builder("emporia.orders.filled.total")
                .description("The total number of filled orders")
                .register(meters);
        cancelledCounter = Counter.builder("emporia.orders.cancelled.total")
                .description("The total number of cancelled orders")
                .register(meters);
        rejectedCounter = Counter.builder("emporia.orders.rejected.total")
                .description("The total number of rejected orders")
                .register(meters);

        // In-process Gauges for dashboard compatibility without DB queries
        meters.gauge("total_orders", totalOrders, AtomicLong::get);
        meters.gauge("live_orders", liveOrders, AtomicLong::get);
        meters.gauge("cancelled_orders", cancelledOrders, AtomicLong::get);
        meters.gauge("filled_orders", filledOrders, AtomicLong::get);
        meters.gauge("rejected_orders", rejectedOrders, AtomicLong::get);
        meters.gauge("pending_cancel_orders", pendingCancelOrders, AtomicLong::get);
        meters.gauge("none_status_orders", 0, ignored -> 0);
        meters.gauge("pending_live_orders", 0, ignored -> 0);
    }

    /** Called when a new order transitions to LIVE (CREATE accepted). */
    void orderCreated() {
        createdCounter.increment();
        totalOrders.incrementAndGet();
        liveOrders.incrementAndGet();
    }

    /** Called when a cancel is requested (target status → CANCELLED). */
    void cancelRequested() {
        pendingCancelOrders.incrementAndGet();
    }

    /** Called when a cancel is confirmed (status → CANCELLED). */
    void orderCancelled() {
        cancelledCounter.increment();
        liveOrders.decrementAndGet();
        cancelledOrders.incrementAndGet();
        pendingCancelOrders.decrementAndGet();
    }

    /** Called when an order transitions to FILLED. */
    void orderFilled() {
        filledCounter.increment();
        liveOrders.decrementAndGet();
        filledOrders.incrementAndGet();
    }

    /** Called when an order is REJECTED before any fill. */
    void orderRejected() {
        rejectedCounter.increment();
        liveOrders.decrementAndGet();
        rejectedOrders.incrementAndGet();
    }
}

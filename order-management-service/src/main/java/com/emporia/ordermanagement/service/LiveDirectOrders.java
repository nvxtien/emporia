package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderView;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The set of orders this service believes the venue is currently responsible
 * for: live, top-level, and routed direct rather than through a strategy.
 *
 * <h2>Why this is a class and not two identical stream pipelines</h2>
 * <p>Two callers need exactly this set and must never disagree about it.
 * {@code ExecutionRecoveryController} serves it over HTTP so the venue can
 * rebuild its lifecycle projection, and the startup reconciliation guard
 * compares it against what the venue actually holds. If those two definitions
 * drifted - one filtering on destination, the other forgetting to - the guard
 * would report a disagreement that only its own query had invented.
 *
 * <p>The filter is deliberately narrow. Child orders are excluded because the
 * venue is asked about parents; non-DMA orders are excluded because a strategy
 * order is not resting anywhere, its children are.
 */
@Service
public class LiveDirectOrders {

    private static final List<OrderStatus> ACTIVE =
            List.of(OrderStatus.LIVE, OrderStatus.PARTIALLY_FILLED);

    private final TradingOrderRepository orders;

    public LiveDirectOrders(TradingOrderRepository orders) {
        this.orders = orders;
    }

    /** Live top-level DMA orders, oldest first. */
    @Transactional(readOnly = true)
    public List<OrderView> current() {
        return parents().stream()
                .filter(LiveDirectOrders::isDirect)
                .map(TradingOrder::view)
                .toList();
    }

    /** Live top-level orders of every destination, for callers that split them. */
    @Transactional(readOnly = true)
    public List<TradingOrder> parents() {
        return orders.findByStatusInAndParentOrderIdIsNullOrderByCreatedAtAsc(ACTIVE);
    }

    /** True when the venue, rather than a strategy, is holding this order. */
    public static boolean isDirect(TradingOrder order) {
        return "DMA".equalsIgnoreCase(order.getDestination());
    }
}

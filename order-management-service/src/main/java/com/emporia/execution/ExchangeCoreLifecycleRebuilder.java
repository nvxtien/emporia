package com.emporia.execution;

import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import exchange.core2.core.common.api.dma.DmaLifecycleResult;
import exchange.core2.core.common.api.dma.DmaLifecycleSnapshot;
import exchange.core2.core.common.api.dma.DmaLimitOrder;
import exchange.core2.core.common.api.dma.DmaNewOrder;
import exchange.core2.core.common.api.dma.DmaOrderResult;
import exchange.core2.core.common.api.dma.DmaOrderState;
import exchange.core2.core.common.api.dma.DmaOrderStatus;
import exchange.core2.core.common.api.dma.DmaProtectedMarketOrder;
import exchange.core2.core.common.cmd.CommandResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds the venue's DMA lifecycle projection from order-management state.
 *
 * <p>Exists because the exchange-core journal restores the matching engine but
 * not the lifecycle: the lifecycle lives on the API side and journal replay
 * drives the disruptor, so after a journalled recovery the book holds orders
 * the lifecycle has no record of, and every later operation on them fails with
 * {@code unknown lifecycle order}. See rework/WAL_LIFECYCLE_GAP.md.
 *
 * <p>This works only because every identity the venue uses is a pure function
 * of fields order-management already stores — order id, order version, owner
 * subject — so {@code coreOrderId}, {@code clientId} and {@code deliveryId} are
 * recomputable exactly. Those functions are deliberately reused from
 * {@link ExchangeCoreExecutionVenueGateway} rather than reimplemented: two
 * copies that drift would produce a projection keyed differently from the one
 * the live path writes, which would defeat the whole exercise silently.
 */
final class ExchangeCoreLifecycleRebuilder {
    private static final Logger log = LoggerFactory.getLogger(ExchangeCoreLifecycleRebuilder.class);

    /** Must match the gateway's, or a rebuilt sweep carries a different cap. */
    private final BigDecimal slippageBps;

    ExchangeCoreLifecycleRebuilder(BigDecimal slippageBps) {
        this.slippageBps = slippageBps;
    }

    /**
     * Rebuilds a lifecycle projection covering every order still capable of
     * further execution.
     *
     * <p>Terminal orders are excluded: a filled, cancelled or rejected order has
     * nothing left to act on, and carrying it would only enlarge the projection.
     *
     * <p>Orders whose recorded quantities cannot form a valid lifecycle state
     * are skipped individually rather than failing the rebuild. One inconsistent
     * row should not keep the venue down, but it must be visible, so each is
     * logged and counted.
     */
    DmaLifecycleSnapshot rebuild(List<OrderView> orders) {
        Map<Long, DmaOrderState> states = new HashMap<>();
        List<DmaLifecycleSnapshot.CompletedDelivery> deliveries = new ArrayList<>();
        int skipped = 0;

        for (OrderView order : orders) {
            if (isTerminal(order.status())) continue;
            try {
                DmaNewOrder request = request(order);
                DmaOrderState state = state(order, request);
                states.put(request.orderId(), state);
                deliveries.add(new DmaLifecycleSnapshot.CompletedDelivery(
                        request, result(request, state)));
            } catch (RuntimeException inconsistent) {
                skipped++;
                log.warn("Skipping order {} while rebuilding the venue lifecycle: {}",
                        order.id(), inconsistent.getMessage());
            }
        }

        log.info("Rebuilt venue lifecycle from order-management: {} orders restored, {} skipped",
                states.size(), skipped);
        return new DmaLifecycleSnapshot(states, deliveries);
    }

    private static boolean isTerminal(OrderStatus status) {
        return status == OrderStatus.FILLED
                || status == OrderStatus.CANCELLED
                || status == OrderStatus.REJECTED;
    }

    /**
     * Rebuilds the venue request exactly as the submit path would have sent it,
     * including the operation name, which is part of the delivery id.
     */
    private DmaNewOrder request(OrderView order) {
        long quantity = ExchangeCoreExecutionVenueGateway.quantitySteps(order.quantity(), order.listing());
        if (order.type() == OrderType.MARKET || ExchangeCoreExecutionVenueGateway.sweeps(order)) {
            return new DmaProtectedMarketOrder(
                    ExchangeCoreExecutionVenueGateway.deliveryId(order, "submit-protected"),
                    ExchangeCoreExecutionVenueGateway.coreOrderId(order),
                    ExchangeCoreExecutionVenueGateway.clientId(order),
                    ExchangeCoreExecutionVenueGateway.symbolId(order.listing()),
                    ExchangeCoreExecutionVenueGateway.side(order.side()),
                    ExchangeCoreExecutionVenueGateway.protectionPriceTicks(order, slippageBps),
                    quantity);
        }
        return new DmaLimitOrder(
                ExchangeCoreExecutionVenueGateway.deliveryId(order, "submit"),
                ExchangeCoreExecutionVenueGateway.coreOrderId(order),
                ExchangeCoreExecutionVenueGateway.clientId(order),
                ExchangeCoreExecutionVenueGateway.symbolId(order.listing()),
                ExchangeCoreExecutionVenueGateway.side(order.side()),
                ExchangeCoreExecutionVenueGateway.priceTicks(order),
                quantity);
    }

    /**
     * Derives the lifecycle state from the recorded quantities rather than
     * translating the order-management status.
     *
     * <p>{@code DmaOrderState} enforces that quantities, status and version
     * agree, and rejects the combination otherwise. Deriving the status from the
     * quantities cannot disagree with them; mapping the two independently could,
     * and would fail at the constructor for rows that are merely unusual.
     */
    private static DmaOrderState state(OrderView order, DmaNewOrder request) {
        long filled = steps(order.tradedQuantity(), order);
        long remaining = steps(order.remainingQuantity(), order);

        if (remaining <= 0) {
            throw new IllegalArgumentException(
                    "a non-terminal order must have quantity remaining, found " + remaining);
        }
        if (filled + remaining != request.quantity()) {
            throw new IllegalArgumentException(
                    "traded + remaining (" + filled + " + " + remaining
                            + ") does not equal the order quantity " + request.quantity());
        }

        // LIVE and PARTIALLY_FILLED both require version > 0; a version of 0
        // is only valid for NEW, which no recovered order can be.
        long version = Math.max(1, order.version());
        return new DmaOrderState(
                request,
                filled > 0 ? DmaOrderStatus.PARTIALLY_FILLED : DmaOrderStatus.LIVE,
                filled,
                0,
                0,
                remaining,
                version);
    }

    /**
     * Converts a recorded quantity to venue steps, allowing zero.
     *
     * <p>The gateway's {@code quantitySteps} rejects zero, which is right on the
     * submit path — an order for nothing is a bug. Here zero is the normal case:
     * every unfilled order has traded zero.
     */
    private static long steps(BigDecimal quantity, OrderView order) {
        if (quantity == null || quantity.signum() == 0) {
            return 0L;
        }
        return ExchangeCoreExecutionVenueGateway.quantitySteps(quantity, order.listing());
    }

    /**
     * Synthesises the response the venue gave when it accepted this order, so a
     * redelivery of that command after a crash is recognised as a duplicate
     * rather than executed twice.
     *
     * <p>Only the current {@code (id, version, operation)} can be seeded:
     * order-management records the order's state, not the history of delivery
     * ids the venue has answered. That is the delivery a post-crash
     * redelivery repeats, so it is the one that matters; deduplication of
     * superseded versions is not restored.
     */
    private static DmaLifecycleResult result(DmaNewOrder request, DmaOrderState state) {
        return new DmaLifecycleResult(
                request.deliveryId(),
                new DmaOrderResult(
                        request.orderId(), CommandResultCode.SUCCESS, List.of(),
                        state.filledQuantity(), 0),
                state,
                false);
    }
}

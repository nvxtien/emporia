package com.emporia.execution;

import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderView;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Order-level reconciliation between order-management's record of live
 * orders and the exchange-core matching engine's own resting-order index.
 *
 * <p>Detects one specific class of drift: an order order-management believes
 * is LIVE or PARTIALLY_FILLED that the venue has no record of. That is the
 * same condition that otherwise only surfaces as "unknown lifecycle order" on
 * the next command sent for that order (see {@link ExchangeCoreLifecycleRebuilder})
 * - this endpoint finds it by inspection instead of waiting for that command
 * to fail.
 *
 * <p>One-directional by design: it checks every order-management order
 * against the venue, not every venue order against order-management. The
 * reverse direction would need to enumerate every symbol's whole resting
 * book, which the venue's per-client report API does not offer cheaply, and
 * no code path in this system creates a venue order without first writing an
 * order-management row - so this direction covers the drift that is
 * actually reachable.
 */
@Component
@Endpoint(id = "reconciliation")
class ReconciliationEndpoint {

    private final TradingDataClient tradingData;
    private final ExchangeCoreExecutionVenueGateway gateway;

    ReconciliationEndpoint(TradingDataClient tradingData, ExchangeCoreExecutionVenueGateway gateway) {
        this.tradingData = tradingData;
        this.gateway = gateway;
    }

    @ReadOperation
    public Report reconcile() {
        List<OrderView> liveOrders = tradingData.recoverable().directOrders().stream()
                .filter(order -> !isTerminal(order.status()))
                .toList();

        Map<Long, List<OrderView>> byClient = liveOrders.stream()
                .collect(Collectors.groupingBy(ExchangeCoreExecutionVenueGateway::clientId));

        List<String> missing = new ArrayList<>();
        List<String> ghostOrders = new ArrayList<>();

        for (Map.Entry<Long, List<OrderView>> entry : byClient.entrySet()) {
            Long clientId = entry.getKey();
            Set<Long> restingOnVenue = gateway.openOrderIds(clientId).join();
            
            // Direction 1: OMS -> Venue check
            Set<Long> expectedCoreIds = entry.getValue().stream()
                    .map(ExchangeCoreExecutionVenueGateway::coreOrderId)
                    .collect(Collectors.toSet());

            for (OrderView order : entry.getValue()) {
                long expected = ExchangeCoreExecutionVenueGateway.coreOrderId(order);
                if (!restingOnVenue.contains(expected)) {
                    missing.add(order.id() + " (" + order.status() + ")");
                }
            }

            // Direction 2: Reverse Venue -> OMS check (Ghost Order Detection)
            for (Long restingCoreId : restingOnVenue) {
                if (!expectedCoreIds.contains(restingCoreId)) {
                    ghostOrders.add("ghost-core-order-" + restingCoreId);
                }
            }
        }

        return new Report(liveOrders.size(), byClient.size(), missing.size(), missing, ghostOrders.size(), ghostOrders);
    }

    private static boolean isTerminal(OrderStatus status) {
        return status == OrderStatus.FILLED
                || status == OrderStatus.CANCELLED
                || status == OrderStatus.REJECTED;
    }

    public record Report(
            int ordersChecked,
            int clientsChecked,
            int missingCount,
            List<String> missingOrders,
            int ghostCount,
            List<String> ghostOrders
    ) {
    }
}

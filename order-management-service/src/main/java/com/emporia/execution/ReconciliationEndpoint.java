package com.emporia.execution;

import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderView;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Order-level reconciliation between order-management's record of live orders
 * and whatever the configured venue says it is actually holding.
 *
 * <p>Two directions, both from one answer:
 * <ul>
 *   <li><b>Missing</b> - order-management believes an order is live and the
 *       venue has no record of it. Otherwise this surfaces only when the next
 *       command for that order fails.</li>
 *   <li><b>Ghost</b> - the venue is holding something order-management did not
 *       ask about, and has no identity for.</li>
 * </ul>
 *
 * <h2>Why this is venue-agnostic now, and why that mattered</h2>
 * <p>This used to take {@code ExchangeCoreExecutionVenueGateway} as a
 * constructor argument and do the identifier translation itself. It imported
 * nothing from {@code exchange.core2} - the coupling was the type - but that was
 * enough to have it compiled out of the agency artifact entirely.
 *
 * <p>Which was exactly backwards. The agency artifact routes every order to
 * somebody else's venue and holds no position of its own, so **its entire view
 * of the world is derived from another party's records**. It is the build that
 * most needs to check them, and it was the one shipping without any means to.
 * Three of the trading systems surveyed - NautilusTrader, barter-rs and
 * QuantConnect Lean - each got this wrong in their own way, and the shape of the
 * mistake was the same every time: the reconciliation that existed did not cover
 * the thing the system could not otherwise know.
 *
 * <p>So the translation moved behind {@link ExecutionVenueGateway#openOrders},
 * where the venue's identifier scheme belongs, and this endpoint does the part
 * that is the same for every venue: compare two sets.
 */
@Component
@Endpoint(id = "reconciliation")
class ReconciliationEndpoint {

    private final TradingDataClient tradingData;
    private final ExecutionVenueGateway gateway;

    ReconciliationEndpoint(TradingDataClient tradingData, ExecutionVenueGateway gateway) {
        this.tradingData = tradingData;
        this.gateway = gateway;
    }

    @ReadOperation
    public Report reconcile() {
        return reconcile(tradingData.recoverable().directOrders());
    }

    /**
     * The comparison, separated from where the live set came from.
     *
     * <p>The actuator path above fetches it over HTTP from this same process,
     * which means it can only run once the port is open. The startup guard has
     * the same orders in hand already and must run <em>before</em> the port
     * opens, so it calls this directly. One comparison, two sources.
     */
    Report reconcile(List<OrderView> candidates) {
        List<OrderView> liveOrders = candidates.stream()
                .filter(order -> !isTerminal(order.status()))
                .toList();

        VenueOpenOrders venueOrders = gateway.openOrders(liveOrders).join();
        if (!venueOrders.supported()) {
            // Not "nothing is missing". The venue was never asked, so the only
            // honest answer is that this report cannot be produced - reporting
            // zero here would read as a clean bill of health.
            return Report.unavailable(gateway.venueMode(), liveOrders.size());
        }

        Set<java.util.UUID> known = venueOrders.known();
        List<String> missing = new ArrayList<>();
        for (OrderView order : liveOrders) {
            if (!known.contains(order.id())) {
                missing.add(order.id() + " (" + order.status() + ")");
            }
        }
        List<String> ghosts = venueOrders.unknownToCaller();

        return new Report(true, gateway.venueMode(), liveOrders.size(),
                missing.size(), missing, ghosts.size(), ghosts);
    }

    private static boolean isTerminal(OrderStatus status) {
        return status == OrderStatus.FILLED
                || status == OrderStatus.CANCELLED
                || status == OrderStatus.REJECTED;
    }

    public record Report(
            boolean supported,
            String venueMode,
            int ordersChecked,
            int missingCount,
            List<String> missingOrders,
            int ghostCount,
            List<String> ghostOrders
    ) {
        static Report unavailable(String venueMode, int ordersChecked) {
            return new Report(false, venueMode, ordersChecked, 0, List.of(), 0, List.of());
        }
    }
}

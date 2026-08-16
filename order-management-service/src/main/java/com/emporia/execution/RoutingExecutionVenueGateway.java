package com.emporia.execution;

import com.emporia.events.TradingEvents.OrderView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sends each order to a venue gateway, now that there can be more than one.
 *
 * <h2>Why the gateways stopped excluding each other</h2>
 * <p>Each gateway used to carry {@code @ConditionalOnProperty} on
 * {@code emporia.execution.venue-mode}, so exactly one existed and everything
 * downstream could inject {@link ExecutionVenueGateway} and get it. That was
 * right while a deployment did one thing. Branch A needs both in one process:
 * {@code exchange-core} to internalise against Emporia's own capital, and
 * {@code fix} both to hedge the resulting inventory and to route whatever is not
 * internalised. Neither can be the one true gateway any more.
 *
 * <h2>What this deliberately does not do yet</h2>
 * <p>It routes by the configured {@code venue-mode} and nothing else, which is
 * exactly what the conditionals did. <b>Behaviour is unchanged</b>, and that is
 * the point: the existing execution suite is the evidence, the same way the
 * untouched FIX tests were evidence for the session-layer extraction.
 *
 * <p>The per-order decision - is Emporia's quote at least as good as the
 * external venue's, and what has to be recorded to defend that answer to a
 * regulator - belongs to a later phase and is not smuggled in here. This area
 * has already shipped two live-trading bugs from a smaller refactor; one change
 * at a time is the response to that.
 */
@Component
@Primary
class RoutingExecutionVenueGateway implements ExecutionVenueGateway {

    private static final Logger log = LoggerFactory.getLogger(RoutingExecutionVenueGateway.class);

    private final Map<String, ExecutionVenueGateway> byMode;
    private final ExecutionVenueGateway configured;
    private final String venueMode;

    RoutingExecutionVenueGateway(List<ExecutionVenueGateway> gateways,
                                 @Value("${emporia.execution.venue-mode:exchange-core}") String venueMode) {
        this.venueMode = normalise(venueMode);
        // Spring already leaves a bean out of a collection it is being injected
        // into, but relying on that silently would make this constructor's
        // correctness a property of the framework rather than of the code.
        this.byMode = gateways.stream()
                .filter(gateway -> !(gateway instanceof RoutingExecutionVenueGateway))
                .collect(Collectors.toUnmodifiableMap(
                        gateway -> normalise(gateway.venueMode()), Function.identity()));
        this.configured = byMode.get(this.venueMode);
        if (configured == null) {
            throw new IllegalStateException(
                    "emporia.execution.venue-mode=" + venueMode + " has no gateway. Available: "
                            + byMode.keySet()
                            + ". A mode with no gateway means orders would reach no venue at all, which is "
                            + "worse than refusing to start: the service would accept them and lose them.");
        }
        log.info("Venue routing: {} gateway(s) present {}, orders go to {}",
                byMode.size(), byMode.keySet(), this.venueMode);
    }

    /**
     * The gateway serving {@code mode}, or {@code null}. For the phases that
     * need a second venue while orders still flow to the first - hedging
     * inventory out while internalising client flow.
     */
    ExecutionVenueGateway forMode(String mode) {
        return byMode.get(normalise(mode));
    }

    @Override
    public String venueMode() {
        return venueMode;
    }

    @Override
    public void submit(OrderView order) {
        configured.submit(order);
    }

    @Override
    public void modify(OrderView order) {
        configured.modify(order);
    }

    @Override
    public void cancel(OrderView order) {
        configured.cancel(order);
    }

    @Override
    public void recover(OrderView order) {
        configured.recover(order);
    }

    @Override
    public CompletableFuture<VenueOpenOrders> openOrders(List<OrderView> expected) {
        return configured.openOrders(expected);
    }

    private static String normalise(String mode) {
        return mode == null ? "" : mode.strip().toLowerCase(Locale.ROOT);
    }
}

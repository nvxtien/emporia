package com.emporia.execution;

import com.emporia.events.TradingEvents.OrderView;

import java.util.List;
import java.util.concurrent.CompletableFuture;

interface ExecutionVenueGateway {
    void submit(OrderView order);

    void modify(OrderView order);

    void cancel(OrderView order);

    /**
     * Rebuilds local correlation after a restart. Production gateways must not
     * blindly resubmit a live exchange order.
     */
    void recover(OrderView order);

    /**
     * Which {@code emporia.execution.venue-mode} this gateway serves.
     *
     * <p>Declared by the gateway rather than inferred from its bean name, which
     * only matches the class name by coincidence and would break silently on a
     * rename. It exists because the gateways stopped being mutually exclusive:
     * branch A needs {@code exchange-core} to internalise and {@code fix} to
     * hedge and to route what is not internalised, in the same process, so
     * something has to say which is which.
     *
     * <p>Also why this is a declaration and not a registry lookup: the
     * agency artifact, the default build, carries no internalising gateway
     * altogether, and a list that is simply missing an element needs no special
     * case.
     */
    String venueMode();

    /**
     * Which of {@code expected} the venue currently holds open, answered in
     * order-management's own order ids.
     *
     * <p>The translation belongs here rather than in the caller because the
     * identifier scheme is the venue's: {@code exchange-core} derives a
     * {@code long} from the order UUID, a FIX venue answers with its own
     * {@code OrderID}. A caller that had to know which is which would have to
     * know which venue it was talking to - and that coupling is exactly what
     * kept order reconciliation out of the agency artifact, which is the one
     * that needs it most, because an agency broker's positions are entirely
     * derived from somebody else's fills.
     *
     * <p>Default is {@link VenueOpenOrders#unsupported()}, so a gateway that
     * cannot answer says so instead of answering "nothing" - see that class for
     * why the distinction is not cosmetic.
     */
    default CompletableFuture<VenueOpenOrders> openOrders(List<OrderView> expected) {
        return CompletableFuture.completedFuture(VenueOpenOrders.unsupported());
    }
}

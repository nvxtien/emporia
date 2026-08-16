package com.emporia.execution;

import com.emporia.events.TradingEvents.OrderView;

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
     * {@code -Dvn} artifact is built without the internalising gateway
     * altogether, and a list that is simply missing an element needs no special
     * case.
     */
    String venueMode();
}

package com.emporia.execution;

import com.emporia.events.TradingEvents.OrderView;

interface ExecutionVenueGateway {
    void submit(OrderView order);

    void modify(OrderView order);

    void cancel(OrderView order);

    /**
     * Rebuilds local correlation after execution-service restarts. Production
     * gateways must not blindly resubmit a live exchange order.
     */
    void recover(OrderView order);
}

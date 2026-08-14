package com.emporia.ordermanagement.dto;

import com.emporia.events.TradingEvents;

import java.util.List;

/**
 * @param view the object {@code result.payload()} was serialised from, when the
 *             handler still had it. Carried so the caller does not parse the
 *             JSON back into the object that produced it a moment earlier;
 *             null on paths that only have the stored payload, such as a
 *             command replayed from the processed-command cache.
 */
public record ProcessingOutcome(TradingEvents.OrderCommandResult result,
                                List<TradingEvents.OrderDomainEvent> events,
                                Object view) {

    public ProcessingOutcome(TradingEvents.OrderCommandResult result,
                             List<TradingEvents.OrderDomainEvent> events) {
        this(result, events, null);
    }
}
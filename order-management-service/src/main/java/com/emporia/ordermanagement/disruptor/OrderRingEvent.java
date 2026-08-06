package com.emporia.ordermanagement.disruptor;

import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.ordermanagement.dto.ProcessingOutcome;
import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.CompletableFuture;

/**
 * Pre-allocated RingBuffer event slot for LMAX Disruptor zero-allocation processing.
 */
@Getter
@Setter
public class OrderRingEvent {
    private OrderCommand command;
    private ProcessingOutcome outcome;
    private CompletableFuture<ProcessingOutcome> future;
    private Throwable error;
    private boolean warmup;
    private long submittedAtNanos;
    private long startedAtNanos;

    public void reset() {
        this.command = null;
        this.outcome = null;
        this.future = null;
        this.error = null;
        this.warmup = false;
        this.submittedAtNanos = 0L;
        this.startedAtNanos = 0L;
    }

    public com.emporia.events.math.LongPair getCommandIdPair() {
        return command != null ? command.commandIdPair() : com.emporia.events.math.LongPair.ZERO;
    }

    public com.emporia.events.math.LongPair getOrderIdPair() {
        return command != null ? command.orderIdPair() : com.emporia.events.math.LongPair.ZERO;
    }
}

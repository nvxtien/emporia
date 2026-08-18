package com.emporia.ordermanagement.disruptor;

import com.emporia.events.TradingEvents.ExecutionCommand;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.ordermanagement.dto.ProcessingOutcome;
import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.CompletableFuture;

/**
 * Pre-allocated RingBuffer event slot for LMAX Disruptor zero-allocation processing.
 *
 * <p>Carries either an {@link OrderCommand} or an {@link ExecutionCommand},
 * never both - {@link #kind} says which, replacing the previous {@code boolean
 * warmup} flag as the discriminator (LMAX_ARCHITECTURE_REWORK_PLAN.md task
 * 4.1-4.2). {@code OrderCommand} and {@code ExecutionCommand} are unrelated
 * record types with no common interface, so this slot holds both fields
 * directly rather than introducing a shared envelope type.
 */
@Getter
@Setter
public class OrderRingEvent {
    private RingEventKind kind;
    private OrderCommand command;
    private ExecutionCommand executionCommand;
    private ProcessingOutcome outcome;
    private CompletableFuture<ProcessingOutcome> future;
    /**
     * Separate from {@link #future}: an execution command has no
     * {@link ProcessingOutcome} to report, only success/failure. Without this,
     * {@code submitExecutionCommand} had no way to signal a handler failure
     * back to its caller - the error was recorded on the event and then lost
     * when the slot was reset, exactly the kind of silent divergence
     * reconciliation exists to catch, except nothing would have surfaced it
     * until reconciliation ran.
     */
    private CompletableFuture<Void> executionFuture;
    private Throwable error;
    private long submittedAtNanos;
    private long startedAtNanos;

    public void reset() {
        this.kind = null;
        this.command = null;
        this.executionCommand = null;
        this.outcome = null;
        this.future = null;
        this.executionFuture = null;
        this.error = null;
        this.submittedAtNanos = 0L;
        this.startedAtNanos = 0L;
    }
}

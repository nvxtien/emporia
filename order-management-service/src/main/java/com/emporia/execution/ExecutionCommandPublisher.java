package com.emporia.execution;

import com.emporia.events.TradingEvents.ExecutionCommand;
import com.emporia.events.TradingEvents.ExecutionCommandType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;

@Component
class ExecutionCommandPublisher {
    private final Counter fills;
    private final ObservationRegistry observations;
    // LMAX_ARCHITECTURE_REWORK_PLAN.md task 5.1: was ExecutionCommandHandler,
    // called directly. Fills/rejects/venue cancels now go through the OMS
    // ring, the same sequence order commands use (R1), instead of running on
    // whichever ShardedOrderDispatcher shard thread got here.
    private final com.emporia.ordermanagement.disruptor.DisruptorOrderPipeline disruptorOrderPipeline;

    ExecutionCommandPublisher(MeterRegistry meters, ObservationRegistry observations) {
        this(meters, observations, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    ExecutionCommandPublisher(MeterRegistry meters,
                              ObservationRegistry observations,
                              // @Lazy: preserves the exact defensive pattern this field
                              // replaced (ExecutionCommandHandler was @Lazy here for the
                              // same reason) - ExecutionEventConsumer, reached indirectly
                              // through this call chain, depends on DisruptorOrderPipeline
                              // too, and this codebase has already been burned once this
                              // session by an unverified assumption about Spring's eager
                              // bean-creation order.
                              @org.springframework.context.annotation.Lazy @org.springframework.beans.factory.annotation.Autowired(required = false) com.emporia.ordermanagement.disruptor.DisruptorOrderPipeline disruptorOrderPipeline) {
        this.fills = meters.counter("emporia.execution.fills");
        this.observations = observations;
        this.disruptorOrderPipeline = disruptorOrderPipeline;
    }

    void fill(UUID orderId, String deskId, String reference, BigDecimal quantity, BigDecimal price,
              String venue, Instant occurredAt) {
        publish(new ExecutionCommand(SCHEMA_VERSION, deterministic(reference + ":COMMAND"),
                ExecutionCommandType.FILL, orderId, deskId, reference, quantity, price, venue,
                occurredAt, null));
        fills.increment();
    }

    void reject(UUID orderId, String deskId, String reference, String venue, String detail) {
        publish(new ExecutionCommand(SCHEMA_VERSION, deterministic(reference + ":COMMAND"),
                ExecutionCommandType.REJECT, orderId, deskId, reference, null, null, venue,
                Instant.now(), detail));
    }

    void venueCancel(UUID orderId, String deskId, String reference, String venue, String detail) {
        publish(new ExecutionCommand(SCHEMA_VERSION, deterministic(reference + ":COMMAND"),
                ExecutionCommandType.CANCEL, orderId, deskId, reference, null, null, venue,
                Instant.now(), detail));
    }

    /**
     * Records {@code emporia.execution.publish} around the fill/reject/cancel
     * handoff to order-management's own state authority.
     *
     * <p><b>Synchronous from this method's caller's point of view</b>
     * (decided 2026-08-18, LMAX_ARCHITECTURE_REWORK_PLAN.md task 5.1): submits
     * to the OMS ring, then blocks on the returned future with {@code join()}
     * - no timeout, so a slow writer never turns into a silently dropped
     * execution command. This is deliberately not the fully async two-phase
     * shape LMAX itself uses: {@code ExchangeCoreExecutionVenueGateway}'s own
     * try/catch around {@code commands.fill/reject/venueCancel} depends on
     * seeing the real exception synchronously today, and a fill already
     * happened at the venue has nowhere to go but a log line if this were
     * fire-and-forget without a durable execution-input inbox to retry
     * against - which does not exist yet (deferred to Phase 3/4, same as the
     * backpressure note on {@code submitExecutionCommand} itself). Revisit
     * once that inbox exists.
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    private void publish(ExecutionCommand command) {
        if (disruptorOrderPipeline == null) {
            throw new IllegalStateException(
                    "Could not publish execution command: no OMS ring is configured");
        }
        Observation observation = Observation.createNotStarted("emporia.execution.publish", observations)
                .lowCardinalityKeyValue("command_type", command.commandType() == null
                        ? "none" : command.commandType().name().toLowerCase(Locale.ROOT))
                .start();
        try (Observation.Scope ignored = observation.openScope()) {
            disruptorOrderPipeline.submitExecutionCommand(command).join();
            observation.lowCardinalityKeyValue("outcome", "success").stop();
        } catch (java.util.concurrent.CompletionException wrapped) {
            // join() wraps whatever the ring's event handler threw in a
            // CompletionException. Unwrapped here so this method keeps
            // throwing exactly what handle() used to throw directly - the
            // synchronous contract this change is preserving, not merely
            // approximating.
            // PMD flags this as losing wrapped's own stack trace (the join()
            // call site) - correct, and deliberate: rethrowing the original
            // exception object, not a new wrapper around it, is what makes
            // the caller's contract "exactly what handle() used to throw"
            // rather than "an exception carrying the same message."
            Throwable cause = wrapped.getCause() != null ? wrapped.getCause() : wrapped;
            observation.error(cause).lowCardinalityKeyValue("outcome", "error").stop();
            if (cause instanceof RuntimeException runtimeCause) {
                throw runtimeCause;
            }
            throw wrapped;
        } catch (RuntimeException exception) {
            observation.error(exception).lowCardinalityKeyValue("outcome", "error").stop();
            throw exception;
        }
    }

    static UUID deterministic(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}

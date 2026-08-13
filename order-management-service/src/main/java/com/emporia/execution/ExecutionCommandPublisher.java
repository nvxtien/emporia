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
    private final com.emporia.ordermanagement.service.ExecutionCommandHandler executionCommandHandler;

    ExecutionCommandPublisher(MeterRegistry meters, ObservationRegistry observations) {
        this(meters, observations, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    ExecutionCommandPublisher(MeterRegistry meters,
                              ObservationRegistry observations,
                              @org.springframework.context.annotation.Lazy @org.springframework.beans.factory.annotation.Autowired(required = false) com.emporia.ordermanagement.service.ExecutionCommandHandler executionCommandHandler) {
        this.fills = meters.counter("emporia.execution.fills");
        this.observations = observations;
        this.executionCommandHandler = executionCommandHandler;
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
     */
    private void publish(ExecutionCommand command) {
        if (executionCommandHandler == null) {
            throw new IllegalStateException(
                    "Could not publish execution command: no execution command handler is configured");
        }
        Observation observation = Observation.createNotStarted("emporia.execution.publish", observations)
                .lowCardinalityKeyValue("command_type", command.commandType() == null
                        ? "none" : command.commandType().name().toLowerCase(Locale.ROOT))
                .start();
        try (Observation.Scope ignored = observation.openScope()) {
            executionCommandHandler.handle(command);
            observation.lowCardinalityKeyValue("outcome", "success").stop();
        } catch (RuntimeException exception) {
            observation.error(exception).lowCardinalityKeyValue("outcome", "error").stop();
            throw exception;
        }
    }

    static UUID deterministic(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}

package com.emporia.execution;

import com.emporia.events.TradingEvents.ExecutionCommand;
import com.emporia.events.TradingEvents.ExecutionCommandType;
import com.emporia.events.KafkaRoutingKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;

@Component
class ExecutionCommandPublisher {
    private final KafkaTemplate<String, Object> kafka;
    private final String topic;
    private final Counter fills;
    private final ObservationRegistry observations;

    ExecutionCommandPublisher(KafkaTemplate<String, Object> kafka,
                              @Value("${emporia.kafka.executions-topic}") String topic,
                              MeterRegistry meters,
                              ObservationRegistry observations) {
        this.kafka = kafka;
        this.topic = topic;
        this.fills = meters.counter("emporia.execution.fills");
        this.observations = observations;
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
     * Records {@code emporia.execution.publish} until Kafka acknowledges the
     * send. Spring Kafka still records the producer span; this observation adds
     * the business dimension that infrastructure instrumentation cannot infer.
     */
    private void publish(ExecutionCommand command) {
        Observation observation = Observation.createNotStarted("emporia.execution.publish", observations)
                .lowCardinalityKeyValue("command_type", command.commandType() == null
                        ? "none" : command.commandType().name().toLowerCase(Locale.ROOT))
                .start();
        try {
            CompletableFuture<SendResult<String, Object>> send;
            try (Observation.Scope ignored = observation.openScope()) {
                send = kafka.send(topic, KafkaRoutingKeys.executionCommand(command), command);
            }
            if (send == null) {
                stopPublishObservation(observation, null);
                return;
            }
            send.whenComplete((ignored, error) -> stopPublishObservation(observation, error));
        } catch (RuntimeException exception) {
            stopPublishObservation(observation, exception);
            throw exception;
        }
    }

    private static void stopPublishObservation(Observation observation, Throwable error) {
        if (error == null) {
            observation.lowCardinalityKeyValue("outcome", "success").stop();
            return;
        }
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause() : error;
        observation.error(cause).lowCardinalityKeyValue("outcome", "error").stop();
    }

    static UUID deterministic(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}

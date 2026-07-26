package com.emporia.execution;

import com.emporia.events.TradingEvents.ExecutionCommand;
import com.emporia.events.TradingEvents.ExecutionCommandType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;

@Component
class ExecutionCommandPublisher {
    private final KafkaTemplate<String, Object> kafka;
    private final String topic;
    private final Counter fills;

    ExecutionCommandPublisher(KafkaTemplate<String, Object> kafka,
                              @Value("${emporia.kafka.executions-topic}") String topic,
                              MeterRegistry meters) {
        this.kafka = kafka;
        this.topic = topic;
        this.fills = meters.counter("emporia.execution.fills");
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

    private void publish(ExecutionCommand command) {
        kafka.send(topic, command.orderId().toString(), command);
    }

    static UUID deterministic(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}

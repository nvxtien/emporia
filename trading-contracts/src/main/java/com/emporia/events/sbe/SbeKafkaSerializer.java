package com.emporia.events.sbe;

import com.emporia.events.TradingEvents.ExecutionCommand;
import com.emporia.events.TradingEvents.OrderCommandResult;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import org.apache.kafka.common.serialization.Serializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import java.util.Map;

/**
 * Custom Kafka Serializer that encodes trading records using high-performance SBE
 * binary encoding. Falls back to Jackson JSON for unhandled types.
 */
public class SbeKafkaSerializer implements Serializer<Object> {
    private final JacksonJsonSerializer<Object> fallbackJson = new JacksonJsonSerializer<>();

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        fallbackJson.configure(configs, isKey);
    }

    @Override
    public byte[] serialize(String topic, Object data) {
        if (data == null) {
            return null;
        }
        if (data instanceof OrderDomainEvent domainEvent) {
            return SbeEncoderDecoder.encodeOrderDomainEvent(domainEvent);
        }
        if (data instanceof ExecutionCommand execCommand) {
            return SbeEncoderDecoder.encodeExecutionCommand(execCommand);
        }
        if (data instanceof OrderCommandResult commandResult) {
            return SbeEncoderDecoder.encodeOrderCommandResult(commandResult);
        }
        return fallbackJson.serialize(topic, data);
    }

    @Override
    public void close() {
        fallbackJson.close();
    }
}

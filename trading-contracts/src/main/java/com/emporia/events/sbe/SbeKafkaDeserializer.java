package com.emporia.events.sbe;

import org.apache.kafka.common.serialization.Deserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Custom Kafka Deserializer that decodes SBE binary payloads with zero-copy buffer
 * reads, falling back to Jackson JSON for legacy JSON payloads.
 */
public class SbeKafkaDeserializer implements Deserializer<Object> {
    private final JacksonJsonDeserializer<Object> fallbackJson = new JacksonJsonDeserializer<>(Object.class);

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        fallbackJson.configure(configs, isKey);
    }

    @Override
    public Object deserialize(String topic, byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        if (SbeEncoderDecoder.isSbePayload(data)) {
            ByteBuffer buf = ByteBuffer.wrap(data);
            buf.getInt(); // magic
            short msgType = buf.getShort();
            return switch (msgType) {
                case SbeEncoderDecoder.MSG_TYPE_ORDER_DOMAIN_EVENT -> SbeEncoderDecoder.decodeOrderDomainEvent(data);
                case SbeEncoderDecoder.MSG_TYPE_EXECUTION_COMMAND -> SbeEncoderDecoder.decodeExecutionCommand(data);
                case SbeEncoderDecoder.MSG_TYPE_ORDER_COMMAND_RESULT -> SbeEncoderDecoder.decodeOrderCommandResult(data);
                default -> fallbackJson.deserialize(topic, data);
            };
        }
        return fallbackJson.deserialize(topic, data);
    }

    @Override
    public void close() {
        fallbackJson.close();
    }
}

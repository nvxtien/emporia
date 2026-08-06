package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.KafkaRoutingKeys;
import com.emporia.ordermanagement.dto.ProcessingOutcome;
import com.emporia.ordermanagement.model.OrderInputEvent;
import com.emporia.ordermanagement.repository.OrderInputEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class OrderCommandConsumer {
    private final OrderCommandHandler handler;
    private final AsyncDbWriter asyncDbWriter;
    private final KafkaTemplate<String, Object> kafka;
    private final ObjectMapper objectMapper;
    private final String resultsTopic;
    private final String ordersTopic;

    public OrderCommandConsumer(OrderCommandHandler handler, AsyncDbWriter asyncDbWriter,
                                KafkaTemplate<String, Object> kafka, ObjectMapper objectMapper,
                                @Value("${emporia.kafka.results-topic}") String resultsTopic,
                                @Value("${emporia.kafka.orders-topic}") String ordersTopic) {
        this.handler = handler;
        this.asyncDbWriter = asyncDbWriter;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.resultsTopic = resultsTopic;
        this.ordersTopic = ordersTopic;
    }

    // Keep this consumer identity stable across the service rename. Changing it would make
    // Kafka treat the deployment as a new consumer group and replay retained commands.
    @KafkaListener(topics = "${emporia.kafka.commands-topic}", groupId = "order-data-service-v1")
    public void consume(OrderCommand command) throws Exception {
        asyncDbWriter.enqueue(new OrderInputEvent(command, objectMapper.writeValueAsString(command)));
        ProcessingOutcome outcome = handler.handle(command);
        // Non-blocking asynchronous Kafka sends. KafkaTemplate buffers messages in-memory,
        // eliminating all listener-thread blocking on broker round-trips.
        for (OrderDomainEvent event : outcome.events()) {
            kafka.send(ordersTopic, KafkaRoutingKeys.orderEvent(event), event);
        }
        kafka.send(resultsTopic, KafkaRoutingKeys.orderResult(command), outcome.result());
    }
}

package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.ExecutionCommand;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.KafkaRoutingKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
class ExecutionCommandConsumer {
    private final ExecutionCommandHandler handler;
    private final KafkaTemplate<String, Object> kafka;
    private final String ordersTopic;

    ExecutionCommandConsumer(ExecutionCommandHandler handler, KafkaTemplate<String, Object> kafka,
                             @Value("${emporia.kafka.orders-topic}") String ordersTopic) {
        this.handler = handler;
        this.kafka = kafka;
        this.ordersTopic = ordersTopic;
    }

    @KafkaListener(topics = "${emporia.kafka.executions-topic}", groupId = "order-management-executions-v1")
    void consume(ExecutionCommand command) throws Exception {
        // Non-blocking asynchronous Kafka sends for execution rollup events.
        for (OrderDomainEvent event : handler.handle(command)) {
            kafka.send(ordersTopic, KafkaRoutingKeys.orderEvent(event), event);
        }
    }
}

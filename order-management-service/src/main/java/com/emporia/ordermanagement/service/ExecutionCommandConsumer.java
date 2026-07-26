package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.ExecutionCommand;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

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
        for (var event : handler.handle(command)) {
            kafka.send(ordersTopic, event.orderId().toString(), event).get(5, TimeUnit.SECONDS);
        }
    }
}

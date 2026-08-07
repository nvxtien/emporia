package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.ExecutionCommand;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class ExecutionCommandConsumer {
    private final ExecutionCommandHandler handler;

    ExecutionCommandConsumer(ExecutionCommandHandler handler) {
        this.handler = handler;
    }

    // Publishing is ExecutionCommandHandler's job now - it enqueues an outbox
    // row in the same AsyncDbWriter flush that persists the fill/reject/cancel,
    // so this listener doesn't need Kafka at all.
    @KafkaListener(topics = "${emporia.kafka.executions-topic}", groupId = "order-management-executions-v1")
    void consume(ExecutionCommand command) throws Exception {
        handler.handle(command);
    }
}

package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.ordermanagement.dto.ProcessingOutcome;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class OrderCommandConsumer {
    private final OrderCommandHandler handler;
    private final KafkaTemplate<String, Object> kafka;
    private final String resultsTopic;
    private final String ordersTopic;

    public OrderCommandConsumer(OrderCommandHandler handler, KafkaTemplate<String, Object> kafka,
                                @Value("${emporia.kafka.results-topic}") String resultsTopic,
                                @Value("${emporia.kafka.orders-topic}") String ordersTopic) {
        this.handler = handler;
        this.kafka = kafka;
        this.resultsTopic = resultsTopic;
        this.ordersTopic = ordersTopic;
    }

    // Keep this consumer identity stable across the service rename. Changing it would make
    // Kafka treat the deployment as a new consumer group and replay retained commands.
    @KafkaListener(topics = "${emporia.kafka.commands-topic}", groupId = "order-data-service-v1")
    public void consume(OrderCommand command) throws Exception {
        ProcessingOutcome outcome = handler.handle(command);
        for (OrderDomainEvent event : outcome.events()) {
            kafka.send(ordersTopic, event.orderId().toString(), event).get(5, TimeUnit.SECONDS);
        }
        kafka.send(resultsTopic, command.commandId().toString(), outcome.result()).get(5, TimeUnit.SECONDS);
    }
}

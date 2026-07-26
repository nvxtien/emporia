package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.OrderDomainEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class OrderDomainEventStreamConsumer {
    private final OrderStreamService streams;

    OrderDomainEventStreamConsumer(OrderStreamService streams) {
        this.streams = streams;
    }

    @KafkaListener(
            topics = "${emporia.kafka.orders-topic}",
            groupId = "order-stream-${random.uuid}",
            properties = "auto.offset.reset=latest"
    )
    void consume(OrderDomainEvent event) {
        streams.publish(event);
    }
}

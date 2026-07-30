package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OrderDomainEventStreamConsumerTest {

    @Test
    void consumeForwardsEventToOrderStreamService() {
        OrderStreamService streams = mock(OrderStreamService.class);
        OrderDomainEventStreamConsumer consumer = new OrderDomainEventStreamConsumer(streams);

        OrderDomainEvent event = new OrderDomainEvent(
                SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "trader", "desk-a", "CREATED", 1L, OrderStatus.LIVE, Instant.now(), "{}"
        );

        assertThatCode(() -> consumer.consume(event)).doesNotThrowAnyException();
        verify(streams).publish(event);
    }
}

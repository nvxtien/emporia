package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.ExecutionCommand;
import com.emporia.events.TradingEvents.ExecutionCommandType;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionCommandConsumerTest {

    @Test
    void consumeHandlesExecutionCommandAndPublishesEventsToKafka() throws Exception {
        ExecutionCommandHandler handler = mock(ExecutionCommandHandler.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        ExecutionCommandConsumer consumer = new ExecutionCommandConsumer(handler, kafka, "orders-topic");

        UUID orderId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        ExecutionCommand command = new ExecutionCommand(
                SCHEMA_VERSION, commandId, ExecutionCommandType.FILL,
                orderId, "desk-a", "ref-fill",
                new BigDecimal("5"), new BigDecimal("100"), "XNAS", Instant.now(), null
        );

        OrderDomainEvent event = new OrderDomainEvent(
                SCHEMA_VERSION, UUID.randomUUID(), commandId, orderId,
                "trader", "desk-a", "PARTIALLY_FILLED", 2L, OrderStatus.PARTIALLY_FILLED, Instant.now(), "{}"
        );

        when(handler.handle(command)).thenReturn(List.of(event));
        when(kafka.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        assertThatCode(() -> consumer.consume(command)).doesNotThrowAnyException();

        verify(kafka).send(eq("orders-topic"), eq(orderId.toString()), eq(event));
    }
}

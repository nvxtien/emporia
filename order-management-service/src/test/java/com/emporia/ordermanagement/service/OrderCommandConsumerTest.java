package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.CommandType;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderCommandResult;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.ordermanagement.dto.ProcessingOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCommandConsumerTest {

    @Test
    void consumeProcessesCommandAndPublishesEventsAndResultToKafka() throws Exception {
        OrderCommandHandler handler = mock(OrderCommandHandler.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        OrderCommandConsumer consumer = new OrderCommandConsumer(handler, kafka, "results-topic", "orders-topic");

        UUID commandId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        OrderCommand command = new OrderCommand(
                SCHEMA_VERSION, commandId, CommandType.CREATE,
                "trader", Instant.now(), orderId, null, listing(),
                OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"), "DMA", "ref", null, Map.of()
        );

        OrderDomainEvent event = new OrderDomainEvent(
                SCHEMA_VERSION, UUID.randomUUID(), commandId, orderId,
                "trader", "trader", "CREATED", 1L, OrderStatus.LIVE, Instant.now(), "{}"
        );
        OrderCommandResult result = new OrderCommandResult(
                SCHEMA_VERSION, commandId, true, 201, null, "{}"
        );
        ProcessingOutcome outcome = new ProcessingOutcome(result, List.of(event));

        when(handler.handle(command)).thenReturn(outcome);
        when(kafka.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        assertThatCode(() -> consumer.consume(command)).doesNotThrowAnyException();

        verify(kafka).send(eq("orders-topic"), eq(orderId.toString()), eq(event));
        verify(kafka).send(eq("results-topic"), eq(commandId.toString()), eq(result));
    }

    private static ListingSnapshot listing() {
        return new ListingSnapshot(
                1, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq",
                "US", "USD",
                new BigDecimal("0.01"), new BigDecimal("0.01"),
                new BigDecimal("200"), new BigDecimal("198")
        );
    }
}

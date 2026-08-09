package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.CommandType;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderCommandResult;
import com.emporia.ordermanagement.disruptor.DisruptorOrderPipeline;
import com.emporia.ordermanagement.dto.ProcessingOutcome;
import com.emporia.ordermanagement.model.OrderInputEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCommandConsumerTest {

    @Test
    void consumeLogsTheCommandThenHandsItToTheDisruptorPipeline() throws Exception {
        DisruptorOrderPipeline disruptorPipeline = mock(DisruptorOrderPipeline.class);
        AsyncDbWriter asyncDbWriter = mock(AsyncDbWriter.class);
        OrderCommandConsumer consumer = new OrderCommandConsumer(
                disruptorPipeline, new OrderInputEventRecorder(asyncDbWriter, new ObjectMapper()));

        UUID commandId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        OrderCommand command = new OrderCommand(
                SCHEMA_VERSION, commandId, CommandType.CREATE,
                "trader", Instant.now(), orderId, null, listing(),
                com.emporia.events.TradingEvents.OrderSide.BUY, com.emporia.events.TradingEvents.OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"), "DMA", "ref", null, Map.of()
        );

        OrderCommandResult result = new OrderCommandResult(SCHEMA_VERSION, commandId, true, 201, null, "{}");
        ProcessingOutcome outcome = new ProcessingOutcome(result, List.of());
        when(disruptorPipeline.submit(command)).thenReturn(CompletableFuture.completedFuture(outcome));

        assertThatCode(() -> consumer.consume(command)).doesNotThrowAnyException();

        verify(asyncDbWriter).enqueue(any(OrderInputEvent.class));
        verify(disruptorPipeline).submit(command);
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

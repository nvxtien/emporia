package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommandResult;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.ordermanagement.model.OrderEvent;
import com.emporia.ordermanagement.model.ProcessedCommand;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.OrderEventRepository;
import com.emporia.ordermanagement.repository.ProcessedCommandRepository;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AsyncDbWriterTest {
    private final TradingOrderRepository orders = mock(TradingOrderRepository.class);
    private final OrderEventRepository events = mock(OrderEventRepository.class);
    private final ProcessedCommandRepository processed = mock(ProcessedCommandRepository.class);
    private AsyncDbWriter writer;

    @BeforeEach
    void setUp() {
        writer = new AsyncDbWriter(orders, events, processed);
    }

    @Test
    void enqueuesAndFlushesEntitiesInBatch() {
        UUID orderId = UUID.randomUUID();
        ListingSnapshot listing = new ListingSnapshot(1L, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("200"), new BigDecimal("198"));
        TradingOrder order = new TradingOrder(orderId, "trader-1", listing, OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("100"), new BigDecimal("150.00"), "DMA", "ref-1", null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(order, "version", 1L);
        OrderEvent event = new OrderEvent(UUID.randomUUID(), order, "CREATED", "Created", "{}");
        OrderCommandResult result = new OrderCommandResult(SCHEMA_VERSION, UUID.randomUUID(), true, 201, "OK", "{}");
        ProcessedCommand processedCommand = new ProcessedCommand(result);

        writer.enqueue(order);
        writer.enqueue(event);
        writer.enqueue(processedCommand);

        writer.flush();

        verify(orders).saveAll(anyList());
        verify(events).saveAll(anyList());
        verify(processed).saveAll(anyList());
    }
}

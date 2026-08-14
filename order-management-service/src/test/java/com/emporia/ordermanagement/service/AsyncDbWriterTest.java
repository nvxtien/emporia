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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncDbWriterTest {
    private final TradingOrderRepository orders = mock(TradingOrderRepository.class);
    private final OrderEventRepository events = mock(OrderEventRepository.class);
    private final ProcessedCommandRepository processed = mock(ProcessedCommandRepository.class);
    private AsyncDbWriter writer;

    @BeforeEach
    void setUp() {
        writer = new AsyncDbWriter(orders, events, processed);
    }

    /**
     * command_id is the primary key, so a command reaching this insert twice
     * means deduplication let a duplicate order through. ON CONFLICT DO NOTHING
     * absorbs the collision, so the per-row affected count is the only signal
     * that it happened - 0 means the row was already there.
     */
    @Test
    void countsCommandsThatReachedTheDatabaseTwice() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        when(jdbc.batchUpdate(anyString(), anyList(), anyInt(), any()))
                .thenReturn(new int[][]{{1, 0}});
        AsyncDbWriter instrumented = new AsyncDbWriter(orders, events, processed, null, jdbc, null, null, meters);

        instrumented.enqueue(new ProcessedCommand(
                new OrderCommandResult(SCHEMA_VERSION, UUID.randomUUID(), true, 201, null, "{}")));
        instrumented.enqueue(new ProcessedCommand(
                new OrderCommandResult(SCHEMA_VERSION, UUID.randomUUID(), true, 201, null, "{}")));
        instrumented.flush();

        assertThat(meters.counter("emporia.oms.dedup.duplicate_reached_db").count()).isEqualTo(1.0);
    }

    @Test
    void reportsNoDuplicatesWhenEveryRowIsInserted() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        when(jdbc.batchUpdate(anyString(), anyList(), anyInt(), any()))
                .thenReturn(new int[][]{{1, 1}});
        AsyncDbWriter instrumented = new AsyncDbWriter(orders, events, processed, null, jdbc, null, null, meters);

        instrumented.enqueue(new ProcessedCommand(
                new OrderCommandResult(SCHEMA_VERSION, UUID.randomUUID(), true, 201, null, "{}")));
        instrumented.enqueue(new ProcessedCommand(
                new OrderCommandResult(SCHEMA_VERSION, UUID.randomUUID(), true, 201, null, "{}")));
        instrumented.flush();

        assertThat(meters.counter("emporia.oms.dedup.duplicate_reached_db").count()).isZero();
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

    @Test
    void retriesRecordsDrainedByAFailedFlush() {
        when(orders.saveAll(anyList()))
                .thenThrow(new RuntimeException("database unavailable"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        writer.enqueue(testOrder());

        assertThatThrownBy(writer::flush)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("database unavailable");

        writer.flush();

        verify(orders, times(2)).saveAll(anyList());
    }

    @Test
    void failedFlushDoesNotCompactWalUntilRestoredRecordsPersist() {
        MemoryMappedWalLogger wal = mock(MemoryMappedWalLogger.class);
        org.mockito.Mockito.when(wal.isEnabled()).thenReturn(true);
        AsyncDbWriter writerWithWal = new AsyncDbWriter(orders, events, processed, null, null, wal, null, null);
        when(orders.saveAll(anyList()))
                .thenThrow(new RuntimeException("database unavailable"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        writerWithWal.enqueue(testOrder());

        assertThatThrownBy(writerWithWal::flush)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("database unavailable");
        verify(wal, never()).compactToSafePoint();

        writerWithWal.flush();

        verify(wal, times(1)).compactToSafePoint();
    }

    @Test
    void reclaimWaitsUntilEveryQueueDrainsBeforeCompactingTheLog() {
        MemoryMappedWalLogger wal = mock(MemoryMappedWalLogger.class);
        org.mockito.Mockito.when(wal.isEnabled()).thenReturn(true);
        AsyncDbWriter writerWithWal = new AsyncDbWriter(orders, events, processed, null, null, wal, null, null);
        // One more than a single flush batch, so one order is still queued
        // when reclaimWriteAheadLog runs its emptiness check.
        for (int i = 0; i < 501; i++) {
            writerWithWal.enqueue(testOrder());
        }

        writerWithWal.flush();
        verify(wal, never()).compactToSafePoint();

        writerWithWal.flush();
        verify(wal, times(1)).compactToSafePoint();
    }

    private static TradingOrder testOrder() {
        ListingSnapshot listing = new ListingSnapshot(1L, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("200"), new BigDecimal("198"));
        return new TradingOrder(UUID.randomUUID(), "trader-1", listing, OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("100"), new BigDecimal("150.00"), "DMA", "ref-1", null, null, null);
    }
}

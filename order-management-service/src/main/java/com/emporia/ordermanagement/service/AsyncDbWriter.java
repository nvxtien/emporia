package com.emporia.ordermanagement.service;

import com.emporia.ordermanagement.model.OrderEvent;
import com.emporia.ordermanagement.model.OrderOutboxRecord;
import com.emporia.ordermanagement.model.ProcessedCommand;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.OrderEventRepository;
import com.emporia.ordermanagement.repository.ProcessedCommandRepository;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * High-performance Write-Behind asynchronous database batch writer.
 *
 * <p>Enqueues database entity modifications into non-blocking memory queues,
 * persisting them asynchronously using Raw JDBC Batching (`JdbcTemplate.batchUpdate`).
 * Bypasses Hibernate ORM entity tracking, first-level cache, and reflection overhead.
 */
@Service
public class AsyncDbWriter {
    private static final int BATCH_SIZE = 500;

    private final TradingOrderRepository orders;
    private final OrderEventRepository events;
    private final ProcessedCommandRepository processed;
    private final com.emporia.ordermanagement.repository.OrderInputEventRepository inputEvents;
    private final com.emporia.ordermanagement.repository.OrderOutboxRepository outbox;
    private final JdbcTemplate jdbcTemplate;
    /** Rewound once its records are persisted; null when running without a log. */
    private final MemoryMappedWalLogger wal;

    private final ConcurrentLinkedQueue<TradingOrder> orderQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<OrderEvent> eventQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ProcessedCommand> processedQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<com.emporia.ordermanagement.model.OrderInputEvent> inputEventQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<OrderOutboxRecord> outboxQueue = new ConcurrentLinkedQueue<>();

    // Pre-allocated reusable batch buffers per thread / flush iteration
    private final TradingOrder[] orderBatchBuffer = new TradingOrder[BATCH_SIZE];
    private final OrderEvent[] eventBatchBuffer = new OrderEvent[BATCH_SIZE];
    private final ProcessedCommand[] processedBatchBuffer = new ProcessedCommand[BATCH_SIZE];
    private final com.emporia.ordermanagement.model.OrderInputEvent[] inputEventBatchBuffer = new com.emporia.ordermanagement.model.OrderInputEvent[BATCH_SIZE];
    private final OrderOutboxRecord[] outboxBatchBuffer = new OrderOutboxRecord[BATCH_SIZE];

    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    public AsyncDbWriter(TradingOrderRepository orders, OrderEventRepository events, ProcessedCommandRepository processed) {
        this(orders, events, processed, null, null, null, null, null);
    }

    // Marks the constructor Spring injects through. Without it there are two
    // candidates and none nominated, so Spring falls back to a no-arg
    // constructor that does not exist and the context fails to start with
    // "No default constructor found". Annotating the parameters is not enough:
    // that controls whether a dependency is required, not which constructor is
    // the injection point.
    @org.springframework.beans.factory.annotation.Autowired
    public AsyncDbWriter(TradingOrderRepository orders, OrderEventRepository events,
                         ProcessedCommandRepository processed,
                         com.emporia.ordermanagement.repository.OrderInputEventRepository inputEvents,
                         com.emporia.ordermanagement.repository.OrderOutboxRepository outbox,
                         JdbcTemplate jdbcTemplate,
                         MemoryMappedWalLogger wal,
                         org.springframework.transaction.support.TransactionTemplate transactionTemplate) {
        this.orders = orders;
        this.events = events;
        this.processed = processed;
        this.inputEvents = inputEvents;
        this.outbox = outbox;
        this.jdbcTemplate = jdbcTemplate;
        this.wal = wal;
        this.transactionTemplate = transactionTemplate;
    }

    public void enqueue(TradingOrder order) {
        if (order != null) orderQueue.add(order);
    }

    public void enqueue(OrderEvent event) {
        if (event != null) eventQueue.add(event);
    }

    public void enqueue(ProcessedCommand command) {
        if (command != null) processedQueue.add(command);
    }

    public void enqueue(com.emporia.ordermanagement.model.OrderInputEvent inputEvent) {
        if (inputEvent != null) inputEventQueue.add(inputEvent);
    }

    public void enqueue(OrderOutboxRecord outboxRecord) {
        if (outboxRecord != null) outboxQueue.add(outboxRecord);
    }

    // Configurable so scripts/perf/wal-recovery-check.sh can widen it well
    // beyond HTTP round-trip time, guaranteeing a burst lands mid-window rather
    // than racing a 10ms flush that a single curl process cannot beat.
    @Scheduled(fixedDelayString = "${emporia.async-db-writer.flush-delay-ms:10}")
    public synchronized void flush() {
        if (transactionTemplate != null) {
            transactionTemplate.executeWithoutResult(status -> doFlush());
        } else {
            doFlush();
        }
    }

    private void doFlush() {
        flushOrders();
        flushEvents();
        flushProcessed();
        flushInputEvents();
        flushOutbox();

        reclaimWriteAheadLog();
    }

    /**
     * Reclaims the write-ahead log space covering rows this flush persisted.
     *
     * <p>Only once the queues are empty: until then some enqueued row is still
     * unwritten, and its log record is what would recover it. Compaction keeps
     * whatever was accepted while the flush ran, so the log holds the in-flight
     * window rather than a history - which is what lets a fixed-size mapping
     * serve indefinitely instead of filling and refusing orders.
     */
    private void reclaimWriteAheadLog() {
        if (wal == null || !wal.isEnabled()) return;
        if (!orderQueue.isEmpty() || !eventQueue.isEmpty()
                || !processedQueue.isEmpty() || !inputEventQueue.isEmpty()
                || !outboxQueue.isEmpty()) {
            return;
        }
        wal.compactToSafePoint();
    }

    @PreDestroy
    public void onShutdown() {
        flush();
    }

    private void flushOrders() {
        if (orderQueue.isEmpty()) return;
        int count = 0;
        while (count < BATCH_SIZE) {
            TradingOrder order = orderQueue.poll();
            if (order == null) break;
            orderBatchBuffer[count] = order;
            count++;
        }
        if (count > 0) {
            List<TradingOrder> batch = Arrays.asList(orderBatchBuffer).subList(0, count);
            if (jdbcTemplate != null) {
                flushOrdersJdbc(batch);
            } else {
                orders.saveAll(batch);
            }
            Arrays.fill(orderBatchBuffer, 0, count, null);
        }
    }

    private void flushEvents() {
        if (eventQueue.isEmpty()) return;
        int count = 0;
        while (count < BATCH_SIZE) {
            OrderEvent event = eventQueue.poll();
            if (event == null) break;
            eventBatchBuffer[count] = event;
            count++;
        }
        if (count > 0) {
            List<OrderEvent> batch = Arrays.asList(eventBatchBuffer).subList(0, count);
            if (jdbcTemplate != null) {
                flushEventsJdbc(batch);
            } else {
                events.saveAll(batch);
            }
            Arrays.fill(eventBatchBuffer, 0, count, null);
        }
    }

    private void flushProcessed() {
        if (processedQueue.isEmpty()) return;
        int count = 0;
        while (count < BATCH_SIZE) {
            ProcessedCommand item = processedQueue.poll();
            if (item == null) break;
            processedBatchBuffer[count] = item;
            count++;
        }
        if (count > 0) {
            List<ProcessedCommand> batch = Arrays.asList(processedBatchBuffer).subList(0, count);
            if (jdbcTemplate != null) {
                flushProcessedJdbc(batch);
            } else {
                processed.saveAll(batch);
            }
            Arrays.fill(processedBatchBuffer, 0, count, null);
        }
    }

    private void flushOrdersJdbc(List<TradingOrder> batch) {
        String sql = """
            INSERT INTO emporia_order_data.trading_order (
                id, entity_version, user_subject, desk_id, listing_id, listing_symbol, exchange_mic, currency, tick_size, size_increment,
                listing_version, listing_name, market_symbol, exchange_name, country_code, reference_price, previous_close,
                order_side, order_type, quantity, limit_price, remaining_quantity, traded_quantity, average_trade_price,
                order_status, target_status, destination, originator_reference, parent_order_id, root_order_id,
                execution_parameters, error_message, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                entity_version = EXCLUDED.entity_version,
                order_status = EXCLUDED.order_status,
                target_status = EXCLUDED.target_status,
                remaining_quantity = EXCLUDED.remaining_quantity,
                traded_quantity = EXCLUDED.traded_quantity,
                average_trade_price = EXCLUDED.average_trade_price,
                error_message = EXCLUDED.error_message,
                updated_at = EXCLUDED.updated_at
            """;
        jdbcTemplate.batchUpdate(sql, batch, batch.size(), (PreparedStatement ps, TradingOrder o) -> {
            ps.setObject(1, o.getId());
            ps.setLong(2, o.getVersion() == null ? 0L : o.getVersion());
            ps.setString(3, o.getUserSubject());
            ps.setString(4, o.getDeskId());
            ps.setLong(5, o.getListing() == null ? 0L : o.getListing().getId());
            ps.setString(6, o.getListing() == null ? "" : o.getListing().getSymbol());
            ps.setString(7, o.getListing() == null ? "" : o.getListing().getExchangeMic());
            ps.setString(8, o.getListing() == null ? "" : o.getListing().getCurrency());
            ps.setBigDecimal(9, o.getListing() == null ? null : o.getListing().getTickSize());
            ps.setBigDecimal(10, o.getListing() == null ? null : o.getListing().getSizeIncrement());
            // These seven are NOT NULL with no default. Omitting them made every
            // insert of a new order fail; only the ON CONFLICT update path,
            // which never touches them, appeared to work.
            ps.setInt(11, o.getListing() == null ? 0 : o.getListing().getVersion());
            ps.setString(12, o.getListing() == null ? "" : o.getListing().getName());
            ps.setString(13, o.getListing() == null ? "" : o.getListing().getMarketSymbol());
            ps.setString(14, o.getListing() == null ? "" : o.getListing().getExchangeName());
            ps.setString(15, o.getListing() == null ? "" : o.getListing().getCountryCode());
            ps.setBigDecimal(16, o.getListing() == null ? null : o.getListing().getReferencePrice());
            ps.setBigDecimal(17, o.getListing() == null ? null : o.getListing().getPreviousClose());
            ps.setString(18, o.getSide() == null ? null : o.getSide().name());
            ps.setString(19, o.getType() == null ? null : o.getType().name());
            ps.setBigDecimal(20, o.getQuantity());
            ps.setBigDecimal(21, o.getLimitPrice());
            ps.setBigDecimal(22, o.getRemainingQuantity());
            ps.setBigDecimal(23, o.getTradedQuantity());
            ps.setBigDecimal(24, o.getAverageTradePrice());
            ps.setString(25, o.getStatus() == null ? null : o.getStatus().name());
            ps.setString(26, o.getTargetStatus() == null ? null : o.getTargetStatus().name());
            ps.setString(27, o.getDestination());
            ps.setString(28, o.getOriginatorReference());
            if (o.getParentOrderId() != null) ps.setObject(29, o.getParentOrderId()); else ps.setNull(29, Types.OTHER);
            ps.setObject(30, o.getRootOrderId());
            ps.setString(31, o.getExecutionParameters());
            ps.setString(32, o.getErrorMessage());
            ps.setTimestamp(33, o.getCreatedAt() == null ? null : Timestamp.from(o.getCreatedAt()));
            ps.setTimestamp(34, o.getUpdatedAt() == null ? null : Timestamp.from(o.getUpdatedAt()));
        });
    }

    private void flushEventsJdbc(List<OrderEvent> batch) {
        String sql = """
            INSERT INTO emporia_order_data.order_event (
                id, command_id, order_id, order_version, event_type, order_status, quantity, price, message, payload, occurred_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """;
        jdbcTemplate.batchUpdate(sql, batch, batch.size(), (PreparedStatement ps, OrderEvent e) -> {
            ps.setObject(1, e.getId());
            ps.setObject(2, e.getCommandId());
            ps.setObject(3, e.getOrder() == null ? null : e.getOrder().getId());
            ps.setLong(4, e.getOrderVersion());
            ps.setString(5, e.getEventType());
            ps.setString(6, e.getStatus() == null ? null : e.getStatus().name());
            ps.setBigDecimal(7, e.getQuantity());
            ps.setBigDecimal(8, e.getPrice());
            ps.setString(9, e.getMessage());
            ps.setString(10, e.getPayload());
            ps.setTimestamp(11, e.getOccurredAt() == null ? null : Timestamp.from(e.getOccurredAt()));
        });
    }

    private void flushProcessedJdbc(List<ProcessedCommand> batch) {
        String sql = """
            INSERT INTO emporia_order_data.processed_order_command (
                command_id, schema_version, success, http_status, detail, payload, processed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (command_id) DO NOTHING
            """;
        jdbcTemplate.batchUpdate(sql, batch, batch.size(), (PreparedStatement ps, ProcessedCommand p) -> {
            ps.setObject(1, p.result().commandId());
            ps.setInt(2, p.result().schemaVersion());
            ps.setBoolean(3, p.result().success());
            ps.setInt(4, p.result().status());
            ps.setString(5, p.result().detail());
            ps.setString(6, p.result().payload());
            ps.setTimestamp(7, p.getProcessedAt() == null ? null : Timestamp.from(p.getProcessedAt()));
        });
    }

    private void flushInputEvents() {
        if (inputEventQueue.isEmpty()) return;
        int count = 0;
        while (count < BATCH_SIZE) {
            com.emporia.ordermanagement.model.OrderInputEvent item = inputEventQueue.poll();
            if (item == null) break;
            inputEventBatchBuffer[count] = item;
            count++;
        }
        if (count > 0) {
            List<com.emporia.ordermanagement.model.OrderInputEvent> batch = Arrays.asList(inputEventBatchBuffer).subList(0, count);
            if (jdbcTemplate != null) {
                flushInputEventsJdbc(batch);
            } else if (inputEvents != null) {
                inputEvents.saveAll(batch);
            }
            Arrays.fill(inputEventBatchBuffer, 0, count, null);
        }
    }

    private void flushInputEventsJdbc(List<com.emporia.ordermanagement.model.OrderInputEvent> batch) {
        String sql = """
            INSERT INTO emporia_order_data.order_input_event (
                command_id, command_type, user_subject, desk_id, schema_version, payload, received_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
        jdbcTemplate.batchUpdate(sql, batch, batch.size(), (PreparedStatement ps, com.emporia.ordermanagement.model.OrderInputEvent i) -> {
            ps.setObject(1, i.getCommandId());
            ps.setString(2, i.getCommandType() == null ? null : i.getCommandType().name());
            ps.setString(3, i.getUserSubject());
            ps.setString(4, i.getDeskId());
            ps.setInt(5, i.getSchemaVersion());
            ps.setString(6, i.getPayload());
            ps.setTimestamp(7, i.getReceivedAt() == null ? null : Timestamp.from(i.getReceivedAt()));
        });
    }

    private void flushOutbox() {
        if (outboxQueue.isEmpty()) return;
        int count = 0;
        while (count < BATCH_SIZE) {
            OrderOutboxRecord record = outboxQueue.poll();
            if (record == null) break;
            outboxBatchBuffer[count] = record;
            count++;
        }
        if (count > 0) {
            List<OrderOutboxRecord> batch = Arrays.asList(outboxBatchBuffer).subList(0, count);
            if (jdbcTemplate != null) {
                flushOutboxJdbc(batch);
            } else if (outbox != null) {
                outbox.saveAll(batch);
            }
            Arrays.fill(outboxBatchBuffer, 0, count, null);
        }
    }

    private void flushOutboxJdbc(List<OrderOutboxRecord> batch) {
        String sql = """
            INSERT INTO emporia_order_data.order_outbox (
                topic, routing_key, payload_type, payload, status, attempt_count, created_at
            ) VALUES (?, ?, ?, ?, 'PENDING', 0, ?)
            """;
        jdbcTemplate.batchUpdate(sql, batch, batch.size(), (PreparedStatement ps, OrderOutboxRecord r) -> {
            ps.setString(1, r.getTopic());
            ps.setString(2, r.getRoutingKey());
            ps.setString(3, r.getPayloadType() == null ? null : r.getPayloadType().name());
            ps.setString(4, r.getPayload());
            ps.setTimestamp(5, r.getCreatedAt() == null ? null : Timestamp.from(r.getCreatedAt()));
        });
    }
}

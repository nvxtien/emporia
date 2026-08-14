package com.emporia.ordermanagement.service;

import com.emporia.ordermanagement.model.OrderEvent;
import com.emporia.ordermanagement.model.ProcessedCommand;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.OrderEventRepository;
import com.emporia.ordermanagement.repository.ProcessedCommandRepository;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.IntConsumer;

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
    private final JdbcTemplate jdbcTemplate;
    /** Rewound once its records are persisted; null when running without a log. */
    private static final Logger log = LoggerFactory.getLogger(AsyncDbWriter.class);
    private final Counter duplicateCommands;
    private final Counter duplicateOrders;
    private final MemoryMappedWalLogger wal;

    private final ConcurrentLinkedDeque<TradingOrder> orderQueue = new ConcurrentLinkedDeque<>();
    // Ids whose next write is an order's first, so that write can use DO NOTHING
    // and report a collision instead of upserting over a row that already
    // exists. Same queue as every other order write, deliberately: a separate
    // queue could drain out of step and let an update create the row before its
    // own insert ran, which would report a duplicate that never happened.
    private final Set<UUID> firstWriteIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedDeque<OrderEvent> eventQueue = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<ProcessedCommand> processedQueue = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<com.emporia.ordermanagement.model.OrderInputEvent> inputEventQueue = new ConcurrentLinkedDeque<>();

    // Pre-allocated reusable batch buffers per thread / flush iteration
    private final TradingOrder[] orderBatchBuffer = new TradingOrder[BATCH_SIZE];
    private final OrderEvent[] eventBatchBuffer = new OrderEvent[BATCH_SIZE];
    private final ProcessedCommand[] processedBatchBuffer = new ProcessedCommand[BATCH_SIZE];
    private final com.emporia.ordermanagement.model.OrderInputEvent[] inputEventBatchBuffer = new com.emporia.ordermanagement.model.OrderInputEvent[BATCH_SIZE];

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
                         JdbcTemplate jdbcTemplate,
                         MemoryMappedWalLogger wal,
                         org.springframework.transaction.support.TransactionTemplate transactionTemplate,
                         io.micrometer.core.instrument.@Nullable MeterRegistry meters) {
        this.orders = orders;
        this.events = events;
        this.processed = processed;
        this.inputEvents = inputEvents;
        this.jdbcTemplate = jdbcTemplate;
        this.wal = wal;
        this.transactionTemplate = transactionTemplate;
        io.micrometer.core.instrument.MeterRegistry registry = meters == null ? new SimpleMeterRegistry() : meters;
        this.duplicateCommands = registry.counter("emporia.oms.dedup.duplicate_reached_db");
        this.duplicateOrders = registry.counter("emporia.oms.dedup.duplicate_order_reached_db");
    }

    public void enqueue(TradingOrder order) {
        if (order != null) orderQueue.addLast(order);
    }

    /**
     * Enqueues an order's first write, which is the only one that can prove a
     * duplicate.
     *
     * <p>Every later write is an upsert over a row that is expected to be there,
     * so a conflict says nothing. A first write conflicting says an order id the
     * deduplication layer reported as new already exists - and because the
     * upsert would have overwritten it rather than failing on the primary key,
     * nothing else in the system would have noticed.
     */
    public void enqueueNew(TradingOrder order) {
        if (order == null) return;
        firstWriteIds.add(order.getId());
        orderQueue.addLast(order);
    }

    public void enqueue(OrderEvent event) {
        if (event != null) eventQueue.addLast(event);
    }

    public void enqueue(ProcessedCommand command) {
        if (command != null) processedQueue.addLast(command);
    }

    public void enqueue(com.emporia.ordermanagement.model.OrderInputEvent inputEvent) {
        if (inputEvent != null) inputEventQueue.addLast(inputEvent);
    }

    // Configurable so scripts/perf/wal-recovery-check.sh can widen it well
    // beyond HTTP round-trip time, guaranteeing a burst lands mid-window rather
    // than racing a 10ms flush that a single curl process cannot beat.
    @Scheduled(fixedDelayString = "${emporia.async-db-writer.flush-delay-ms:10}")
    public synchronized void flush() {
        PendingFlushBatch batch = drainPendingBatch();
        if (batch.isEmpty()) {
            reclaimWriteAheadLog();
            return;
        }

        try {
            persistBatch(batch);
        } catch (RuntimeException failure) {
            batch.restoreToQueues();
            throw failure;
        } finally {
            batch.clearBuffers();
        }

        reclaimWriteAheadLog();
    }

    private void persistBatch(PendingFlushBatch batch) {
        if (transactionTemplate != null) {
            transactionTemplate.executeWithoutResult(status -> persist(batch));
        } else {
            persist(batch);
        }
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
                || !processedQueue.isEmpty() || !inputEventQueue.isEmpty()) {
            return;
        }
        wal.compactToSafePoint();
    }

    @PreDestroy
    public void onShutdown() {
        flush();
    }

    private PendingFlushBatch drainPendingBatch() {
        return new PendingFlushBatch(
                drain(orderQueue, orderBatchBuffer),
                drain(eventQueue, eventBatchBuffer),
                drain(processedQueue, processedBatchBuffer),
                drain(inputEventQueue, inputEventBatchBuffer));
    }

    private <T> int drain(ConcurrentLinkedDeque<T> queue, T[] buffer) {
        int count = 0;
        while (count < BATCH_SIZE) {
            T item = queue.pollFirst();
            if (item == null) break;
            buffer[count] = item;
            count++;
        }
        return count;
    }

    private <T> void restoreFront(ConcurrentLinkedDeque<T> queue, T[] buffer, int count) {
        for (int index = count - 1; index >= 0; index--) {
            queue.addFirst(buffer[index]);
        }
    }

    private void persist(PendingFlushBatch batch) {
        persistOrders(batch.orderCount);
        persistEvents(batch.eventCount);
        persistProcessed(batch.processedCount);
        persistInputEvents(batch.inputEventCount);
    }

    private void persistOrders(int count) {
        if (count <= 0) return;
        List<TradingOrder> batch = Arrays.asList(orderBatchBuffer).subList(0, count);
        if (jdbcTemplate != null) {
            flushOrdersJdbc(batch);
        } else {
            orders.saveAll(batch);
        }
    }

    private void persistEvents(int count) {
        if (count <= 0) return;
        List<OrderEvent> batch = Arrays.asList(eventBatchBuffer).subList(0, count);
        if (jdbcTemplate != null) {
            flushEventsJdbc(batch);
        } else {
            events.saveAll(batch);
        }
    }

    private void persistProcessed(int count) {
        if (count <= 0) return;
        List<ProcessedCommand> batch = Arrays.asList(processedBatchBuffer).subList(0, count);
        if (jdbcTemplate != null) {
            flushProcessedJdbc(batch);
        } else {
            processed.saveAll(batch);
        }
    }

    private static final String INSERT_ORDER = """
            INSERT INTO emporia_order_data.trading_order (
                id, entity_version, user_subject, desk_id, listing_id, listing_symbol, exchange_mic, currency, tick_size, size_increment,
                listing_version, listing_name, market_symbol, exchange_name, country_code, reference_price, previous_close,
                order_side, order_type, quantity, limit_price, remaining_quantity, traded_quantity, average_trade_price,
                order_status, target_status, destination, originator_reference, parent_order_id, root_order_id,
                execution_parameters, error_message, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
""";

    /**
     * Every write after an order's first is an upsert over a row expected to be
     * there, so a conflict carries no information.
     */
    private static final String ON_CONFLICT_UPDATE = """
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

    /**
     * An order's first write refuses to touch a row that already exists, which
     * both protects that row and turns the collision into a countable event.
     */
    private static final String ON_CONFLICT_NOTHING = "ON CONFLICT (id) DO NOTHING";

    private void flushOrdersJdbc(List<TradingOrder> batch) {
        if (firstWriteIds.isEmpty()) {
            upsertOrders(batch);
            return;
        }
        List<TradingOrder> firstWrites = new ArrayList<>();
        List<TradingOrder> updates = new ArrayList<>();
        Set<UUID> claimed = new HashSet<>();
        for (TradingOrder order : batch) {
            // Only an order's first appearance in this batch is its first write.
            // A create and a modify inside one flush window are two entries
            // pointing at the same object, and calling both inserts would report
            // the second as a duplicate of the first.
            if (firstWriteIds.contains(order.getId()) && claimed.add(order.getId())) {
                firstWrites.add(order);
            } else {
                updates.add(order);
            }
        }
        // Inserts before upserts: an upsert running ahead of its own insert
        // would create the row and make that insert look like a collision.
        if (!firstWrites.isEmpty()) insertNewOrders(firstWrites);
        if (!updates.isEmpty()) upsertOrders(updates);
        // Only on success. A throw above leaves the markers in place, so the
        // restored batch is still treated as first writes on the retry. The
        // reverse - clearing them and then rolling back - would silently drop
        // the check for those orders.
        firstWriteIds.removeAll(claimed);
    }

    private void upsertOrders(List<TradingOrder> batch) {
        jdbcTemplate.batchUpdate(INSERT_ORDER + ON_CONFLICT_UPDATE, batch, batch.size(), this::bindOrder);
    }

    private void insertNewOrders(List<TradingOrder> batch) {
        int[][] affected = jdbcTemplate.batchUpdate(
                INSERT_ORDER + ON_CONFLICT_NOTHING, batch, batch.size(), this::bindOrder);
        reportAbsorbedOrders(affected, batch);
    }

    private void bindOrder(PreparedStatement ps, TradingOrder o) throws java.sql.SQLException {
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
        int[][] affected = jdbcTemplate.batchUpdate(sql, batch, batch.size(), (PreparedStatement ps, ProcessedCommand p) -> {
            ps.setObject(1, p.result().commandId());
            ps.setInt(2, p.result().schemaVersion());
            ps.setBoolean(3, p.result().success());
            ps.setInt(4, p.result().status());
            ps.setString(5, p.result().detail());
            ps.setString(6, p.result().payload());
            ps.setTimestamp(7, p.getProcessedAt() == null ? null : Timestamp.from(p.getProcessedAt()));
        });
        reportAbsorbedDuplicates(affected, batch);
    }

    /**
     * Reports commands the deduplication layer let through.
     *
     * <p>{@code command_id} is the primary key, so a command that reaches this
     * insert twice means the handler failed to recognise it as already
     * processed - which in a trading system means a duplicate order, and a
     * duplicate position. Postgres is already checking this for every command,
     * but {@code ON CONFLICT DO NOTHING} absorbs the collision silently. The
     * per-row affected count is the signal: 0 means the row was already there.
     *
     * <p>This counter should read zero forever. Anything above zero is the
     * number of duplicates that got through, not a warning about one.
     */
    private void reportAbsorbedDuplicates(int[][] affected, List<ProcessedCommand> batch) {
        forEachAbsorbed(affected, batch.size(), index -> {
            ProcessedCommand duplicate = batch.get(index);
            duplicateCommands.increment();
            log.error("Duplicate command reached the database: command_id={} status={}. "
                            + "The deduplication layer did not recognise it as already processed.",
                    duplicate.result().commandId(), duplicate.result().status());
        });
    }

    /**
     * Reports orders the deduplication layer let through.
     *
     * <p>The order-id counterpart of {@link #reportAbsorbedDuplicates}, and the
     * only signal there is for it. {@code trading_order} is upserted on every
     * state change, so a conflict on the normal write path is the expected case
     * and carries nothing; only an order's first write can prove that an id
     * reported as never seen already existed. Left as an upsert, the duplicate
     * would not even fail on the primary key - it would reset a live or filled
     * order's status, traded quantity and average price while keeping the
     * original's identity columns, which nothing downstream would flag.
     *
     * <p>This counter should read zero forever. Anything above zero is the
     * number of orders that got through, not a warning about one.
     */
    private void reportAbsorbedOrders(int[][] affected, List<TradingOrder> batch) {
        forEachAbsorbed(affected, batch.size(), index -> {
            TradingOrder duplicate = batch.get(index);
            duplicateOrders.increment();
            log.error("Duplicate order reached the database: order_id={} status={}. "
                            + "The deduplication layer reported an id as new that already exists; "
                            + "the existing row was left untouched.",
                    duplicate.getId(), duplicate.getStatus());
        });
    }

    /**
     * Walks the per-row affected counts a batch returned and reports the rows
     * the conflict clause absorbed. Postgres groups them, and a driver may
     * return fewer counts than rows, so the index is carried across groups and
     * bounded by the batch rather than trusted from the shape of the result.
     */
    private static void forEachAbsorbed(int[][] affected, int size, IntConsumer onAbsorbed) {
        int index = 0;
        for (int[] group : affected) {
            for (int rows : group) {
                if (rows == 0 && index < size) onAbsorbed.accept(index);
                index++;
            }
        }
    }

    private void persistInputEvents(int count) {
        if (count <= 0) return;
        List<com.emporia.ordermanagement.model.OrderInputEvent> batch = Arrays.asList(inputEventBatchBuffer).subList(0, count);
        if (jdbcTemplate != null) {
            flushInputEventsJdbc(batch);
        } else if (inputEvents != null) {
            inputEvents.saveAll(batch);
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

    private final class PendingFlushBatch {
        private final int orderCount;
        private final int eventCount;
        private final int processedCount;
        private final int inputEventCount;
        private boolean restored;

        private PendingFlushBatch(int orderCount, int eventCount, int processedCount,
                                  int inputEventCount) {
            this.orderCount = orderCount;
            this.eventCount = eventCount;
            this.processedCount = processedCount;
            this.inputEventCount = inputEventCount;
        }

        private boolean isEmpty() {
            return orderCount == 0
                    && eventCount == 0
                    && processedCount == 0
                    && inputEventCount == 0;
        }

        private void restoreToQueues() {
            if (restored) return;
            restoreFront(inputEventQueue, inputEventBatchBuffer, inputEventCount);
            restoreFront(processedQueue, processedBatchBuffer, processedCount);
            restoreFront(eventQueue, eventBatchBuffer, eventCount);
            restoreFront(orderQueue, orderBatchBuffer, orderCount);
            restored = true;
        }

        private void clearBuffers() {
            Arrays.fill(orderBatchBuffer, 0, orderCount, null);
            Arrays.fill(eventBatchBuffer, 0, eventCount, null);
            Arrays.fill(processedBatchBuffer, 0, processedCount, null);
            Arrays.fill(inputEventBatchBuffer, 0, inputEventCount, null);
        }
    }
}

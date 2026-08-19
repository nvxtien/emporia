package com.emporia.ordermanagement.service;

import com.emporia.ordermanagement.model.Execution;
import com.emporia.ordermanagement.model.OrderEvent;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderView;
import com.emporia.ordermanagement.model.ProcessedCommand;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.ExecutionRepository;
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
    private final ExecutionRepository executions;
    private final JdbcTemplate jdbcTemplate;
    /** Rewound once its records are persisted; null when running without a log. */
    private static final Logger log = LoggerFactory.getLogger(AsyncDbWriter.class);
    private final Counter duplicateCommands;
    private final Counter duplicateOrders;
    // LMAX_ARCHITECTURE_REWORK_PLAN.md task 5.2: same oracle as the other two -
    // ExecutionCommandHandler's in-memory dedup should make this impossible,
    // and ON CONFLICT DO NOTHING's affected-row count is what would prove it
    // wrong. Should read zero forever.
    private final Counter duplicateExecutions;
    private final Counter rejectedRows;
    private final MemoryMappedWalLogger wal;

    /**
     * An order write, paired with the state it was enqueued for.
     *
     * <p>The queue used to hold the entity alone and the flush thread read its
     * fields when it got round to them - thirty-four unsynchronised getters on
     * an object the Disruptor writer thread mutates under {@code synchronized}.
     * There is no happens-before between the two, so a flush that ran across a
     * mutation could read half of it: observed in the field as an order carrying
     * a modified {@code remaining_quantity} beside its original
     * {@code quantity}, which the {@code ck_trading_order_fill_accounting} check
     * refused - and one refused row used to stop all persistence permanently.
     *
     * <p>{@code view()} is {@code synchronized} on the same monitor as every
     * mutator, so taking it at enqueue time, on the thread that just made the
     * change, yields a consistent picture of one state. It also fixes the
     * quieter half of the same problem: two enqueues of one order used to write
     * whatever the object held at flush time, twice, rather than the two states
     * they were enqueued for.
     */
    private record PendingOrder(TradingOrder entity, OrderView snapshot) { }

    private final ConcurrentLinkedDeque<PendingOrder> orderQueue = new ConcurrentLinkedDeque<>();
    // Ids whose next write is an order's first, so that write can use DO NOTHING
    // and report a collision instead of upserting over a row that already
    // exists. Same queue as every other order write, deliberately: a separate
    // queue could drain out of step and let an update create the row before its
    // own insert ran, which would report a duplicate that never happened.
    private final Set<UUID> firstWriteIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedDeque<OrderEvent> eventQueue = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<ProcessedCommand> processedQueue = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<com.emporia.ordermanagement.model.OrderInputEvent> inputEventQueue = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Execution> executionQueue = new ConcurrentLinkedDeque<>();

    // Pre-allocated reusable batch buffers per thread / flush iteration
    private final PendingOrder[] orderBatchBuffer = new PendingOrder[BATCH_SIZE];
    private final OrderEvent[] eventBatchBuffer = new OrderEvent[BATCH_SIZE];
    private final ProcessedCommand[] processedBatchBuffer = new ProcessedCommand[BATCH_SIZE];
    private final com.emporia.ordermanagement.model.OrderInputEvent[] inputEventBatchBuffer = new com.emporia.ordermanagement.model.OrderInputEvent[BATCH_SIZE];
    private final Execution[] executionBatchBuffer = new Execution[BATCH_SIZE];

    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    public AsyncDbWriter(TradingOrderRepository orders, OrderEventRepository events, ProcessedCommandRepository processed) {
        this(orders, events, processed, null, null, null, null, null, null);
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
                         io.micrometer.core.instrument.@Nullable MeterRegistry meters,
                         @Nullable ExecutionRepository executions) {
        this.orders = orders;
        this.events = events;
        this.processed = processed;
        this.inputEvents = inputEvents;
        this.executions = executions;
        this.jdbcTemplate = jdbcTemplate;
        this.wal = wal;
        this.transactionTemplate = transactionTemplate;
        io.micrometer.core.instrument.MeterRegistry registry = meters == null ? new SimpleMeterRegistry() : meters;
        this.duplicateCommands = registry.counter("emporia.oms.dedup.duplicate_reached_db");
        this.duplicateOrders = registry.counter("emporia.oms.dedup.duplicate_order_reached_db");
        this.duplicateExecutions = registry.counter("emporia.oms.dedup.duplicate_execution_reached_db");
        this.rejectedRows = registry.counter("emporia.oms.writer.rejected_rows");
    }

    public void enqueue(TradingOrder order) {
        if (order != null) orderQueue.addLast(new PendingOrder(order, order.view()));
    }

    public void enqueue(Execution execution) {
        if (execution != null) executionQueue.addLast(execution);
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
        orderQueue.addLast(new PendingOrder(order, order.view()));
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
            // The transaction has already rolled back, so nothing in this batch
            // landed. Retrying it whole is what used to happen, and one row the
            // database will never accept then blocked every write behind it -
            // observed as 26,070 identical check-constraint failures and not one
            // row persisted, while callers kept receiving 201. Rows are retried
            // individually instead, so a bad one is isolated and named.
            salvage(batch, failure);
        } finally {
            batch.clearBuffers();
        }

        reclaimWriteAheadLog();
    }

    /**
     * Writes a failed batch one row at a time, dropping whichever rows the
     * database refuses and keeping the rest.
     *
     * <p>Each row gets its own transaction, because the failed batch's
     * transaction is already rolled back and PostgreSQL refuses further commands
     * on an aborted one.
     *
     * <p>Dropping a row is not comfortable, and it is the lesser of the two
     * failures available. The alternative - what this replaces - is that a row
     * the database will never accept is retried every ten milliseconds forever
     * while every write behind it waits, so the service keeps answering 201 and
     * persists nothing at all. A drop costs one row and says so; the retry loop
     * costs all of them and says nothing.
     *
     * <p>{@code emporia.oms.writer.rejected_rows} counts them and must stay at
     * zero. The log line carries enough to reconstruct the row by hand.
     */
    private void salvage(PendingFlushBatch batch, RuntimeException failure) {
        log.error("Batch write failed; retrying its rows individually so one row the database "
                + "refuses cannot hold the rest back", failure);
        salvageEach(Arrays.asList(orderBatchBuffer).subList(0, batch.orderCount),
                this::writeOrders,
                row -> "order " + row.snapshot().id() + " status=" + row.snapshot().status()
                        + " quantity=" + row.snapshot().quantity()
                        + " traded=" + row.snapshot().tradedQuantity()
                        + " remaining=" + row.snapshot().remainingQuantity());
        salvageEach(Arrays.asList(eventBatchBuffer).subList(0, batch.eventCount),
                this::writeEvents,
                row -> "order event " + row.getId() + " type=" + row.getEventType());
        salvageEach(Arrays.asList(processedBatchBuffer).subList(0, batch.processedCount),
                this::writeProcessed,
                row -> "processed command " + row.result().commandId() + " status=" + row.result().status());
        salvageEach(Arrays.asList(inputEventBatchBuffer).subList(0, batch.inputEventCount),
                this::writeInputEvents,
                row -> "order input event " + row.getSequenceId());
        salvageEach(Arrays.asList(executionBatchBuffer).subList(0, batch.executionCount),
                this::writeExecutions,
                row -> "execution " + row.getId() + " reference=" + row.getExecutionReference());
    }

    private <T> void salvageEach(List<T> rows, java.util.function.Consumer<List<T>> write,
                                 java.util.function.Function<T, String> describe) {
        for (T row : rows) {
            try {
                inItsOwnTransaction(() -> write.accept(List.of(row)));
            } catch (RuntimeException rowFailure) {
                rejectedRows.increment();
                log.error("Dropped a row the database refused: {}. It is not retried - a row it will "
                                + "never accept would otherwise stop every write behind it.",
                        describe.apply(row), rowFailure);
            }
        }
    }

    private void inItsOwnTransaction(Runnable write) {
        if (transactionTemplate != null) {
            transactionTemplate.executeWithoutResult(status -> write.run());
        } else {
            write.run();
        }
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
                || !processedQueue.isEmpty() || !inputEventQueue.isEmpty()
                || !executionQueue.isEmpty()) {
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
                drain(inputEventQueue, inputEventBatchBuffer),
                drain(executionQueue, executionBatchBuffer));
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

    private void persist(PendingFlushBatch batch) {
        persistOrders(batch.orderCount);
        persistEvents(batch.eventCount);
        persistProcessed(batch.processedCount);
        persistInputEvents(batch.inputEventCount);
        persistExecutions(batch.executionCount);
    }

    private void persistOrders(int count) {
        if (count > 0) writeOrders(Arrays.asList(orderBatchBuffer).subList(0, count));
    }

    private void writeOrders(List<PendingOrder> batch) {
        if (jdbcTemplate != null) {
            flushOrdersJdbc(batch);
        } else {
            orders.saveAll(batch.stream().map(PendingOrder::entity).toList());
        }
    }

    private void persistEvents(int count) {
        if (count > 0) writeEvents(Arrays.asList(eventBatchBuffer).subList(0, count));
    }

    private void writeEvents(List<OrderEvent> batch) {
        if (jdbcTemplate != null) {
            flushEventsJdbc(batch);
        } else {
            events.saveAll(batch);
        }
    }

    private void persistProcessed(int count) {
        if (count > 0) writeProcessed(Arrays.asList(processedBatchBuffer).subList(0, count));
    }

    private void writeProcessed(List<ProcessedCommand> batch) {
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
                quantity = EXCLUDED.quantity,
                limit_price = EXCLUDED.limit_price,
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

    private void flushOrdersJdbc(List<PendingOrder> batch) {
        if (firstWriteIds.isEmpty()) {
            upsertOrders(batch);
            return;
        }
        List<PendingOrder> firstWrites = new ArrayList<>();
        List<PendingOrder> updates = new ArrayList<>();
        Set<UUID> claimed = new HashSet<>();
        for (PendingOrder pending : batch) {
            // Only an order's first appearance in this batch is its first write.
            // A create and a modify inside one flush window are two entries for
            // one order, and calling both inserts would report the second as a
            // duplicate of the first.
            UUID orderId = pending.snapshot().id();
            if (firstWriteIds.contains(orderId) && claimed.add(orderId)) {
                firstWrites.add(pending);
            } else {
                updates.add(pending);
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

    private void upsertOrders(List<PendingOrder> batch) {
        jdbcTemplate.batchUpdate(INSERT_ORDER + ON_CONFLICT_UPDATE, batch, batch.size(), this::bindOrder);
    }

    private void insertNewOrders(List<PendingOrder> batch) {
        int[][] affected = jdbcTemplate.batchUpdate(
                INSERT_ORDER + ON_CONFLICT_NOTHING, batch, batch.size(), this::bindOrder);
        reportAbsorbedOrders(affected, batch);
    }

    /**
     * Binds from the snapshot taken at enqueue time, never from the live entity.
     * Reading the entity here would be a cross-thread read of an object the
     * writer thread is still mutating - see {@link PendingOrder}.
     */
    private void bindOrder(PreparedStatement ps, PendingOrder pending) throws java.sql.SQLException {
        OrderView o = pending.snapshot();
        ListingSnapshot listing = o.listing();
        ps.setObject(1, o.id());
        ps.setLong(2, o.version());
        ps.setString(3, o.ownerSubject());
        ps.setString(4, o.deskId());
        ps.setLong(5, listing == null ? 0L : listing.id());
        ps.setString(6, listing == null ? "" : listing.symbol());
        ps.setString(7, listing == null ? "" : listing.exchangeMic());
        ps.setString(8, listing == null ? "" : listing.currency());
        ps.setBigDecimal(9, listing == null ? null : listing.tickSize());
        ps.setBigDecimal(10, listing == null ? null : listing.sizeIncrement());
        // These seven are NOT NULL with no default. Omitting them made every
        // insert of a new order fail; only the ON CONFLICT update path, which
        // never touches them, appeared to work.
        ps.setInt(11, listing == null ? 0 : listing.version());
        ps.setString(12, listing == null ? "" : listing.name());
        ps.setString(13, listing == null ? "" : listing.marketSymbol());
        ps.setString(14, listing == null ? "" : listing.exchangeName());
        ps.setString(15, listing == null ? "" : listing.countryCode());
        ps.setBigDecimal(16, listing == null ? null : listing.referencePrice());
        ps.setBigDecimal(17, listing == null ? null : listing.previousClose());
        ps.setString(18, o.side() == null ? null : o.side().name());
        ps.setString(19, o.type() == null ? null : o.type().name());
        ps.setBigDecimal(20, o.quantity());
        ps.setBigDecimal(21, o.limitPrice());
        ps.setBigDecimal(22, o.remainingQuantity());
        ps.setBigDecimal(23, o.tradedQuantity());
        ps.setBigDecimal(24, o.averageTradePrice());
        ps.setString(25, o.status() == null ? null : o.status().name());
        ps.setString(26, o.targetStatus() == null ? null : o.targetStatus().name());
        ps.setString(27, o.destination());
        ps.setString(28, o.originatorReference());
        if (o.parentOrderId() != null) ps.setObject(29, o.parentOrderId()); else ps.setNull(29, Types.OTHER);
        ps.setObject(30, o.rootOrderId());
        ps.setString(31, o.executionParameters());
        ps.setString(32, o.errorMessage());
        ps.setTimestamp(33, o.createdAt() == null ? null : Timestamp.from(o.createdAt()));
        ps.setTimestamp(34, o.updatedAt() == null ? null : Timestamp.from(o.updatedAt()));
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
    private void reportAbsorbedOrders(int[][] affected, List<PendingOrder> batch) {
        forEachAbsorbed(affected, batch.size(), index -> {
            OrderView duplicate = batch.get(index).snapshot();
            duplicateOrders.increment();
            log.error("Duplicate order reached the database: order_id={} status={}. "
                            + "The deduplication layer reported an id as new that already exists; "
                            + "the existing row was left untouched.",
                    duplicate.id(), duplicate.status());
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
        if (count > 0) writeInputEvents(Arrays.asList(inputEventBatchBuffer).subList(0, count));
    }

    private void writeInputEvents(List<com.emporia.ordermanagement.model.OrderInputEvent> batch) {
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

    private void persistExecutions(int count) {
        if (count > 0) writeExecutions(Arrays.asList(executionBatchBuffer).subList(0, count));
    }

    private void writeExecutions(List<Execution> batch) {
        if (jdbcTemplate != null) {
            flushExecutionsJdbc(batch);
        } else if (executions != null) {
            executions.saveAll(batch);
        }
    }

    private void flushExecutionsJdbc(List<Execution> batch) {
        String sql = """
            INSERT INTO emporia_order_data.execution (
                id, execution_reference, order_id, quantity, price, venue, executed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """;
        int[][] affected = jdbcTemplate.batchUpdate(sql, batch, batch.size(), (PreparedStatement ps, Execution x) -> {
            ps.setObject(1, x.getId());
            ps.setString(2, x.getExecutionReference());
            ps.setObject(3, x.getOrder() == null ? null : x.getOrder().getId());
            ps.setBigDecimal(4, x.getQuantity());
            ps.setBigDecimal(5, x.getPrice());
            ps.setString(6, x.getVenue());
            ps.setTimestamp(7, x.getExecutedAt() == null ? null : Timestamp.from(x.getExecutedAt()));
        });
        reportAbsorbedExecutionDuplicates(affected, batch);
    }

    /**
     * Reports executions the reference-dedup layer let through.
     *
     * <p>Counterpart of {@link #reportAbsorbedDuplicates} for execution
     * references: {@code id} is deterministic from the reference
     * ({@code ExecutionCommandHandler.deterministic}), so a conflict here means
     * the same fill reached this insert twice, which
     * {@code OrderStateCache.existsExecutionReference} was supposed to catch in
     * memory before {@code applyFillAndRecord} was ever called.
     *
     * <p>This counter should read zero forever, same oracle as the other two.
     */
    private void reportAbsorbedExecutionDuplicates(int[][] affected, List<Execution> batch) {
        forEachAbsorbed(affected, batch.size(), index -> {
            Execution duplicate = batch.get(index);
            duplicateExecutions.increment();
            log.error("Duplicate execution reached the database: id={} reference={}. "
                            + "The execution-reference dedup index did not recognise it as already applied.",
                    duplicate.getId(), duplicate.getExecutionReference());
        });
    }

    private final class PendingFlushBatch {
        private final int orderCount;
        private final int eventCount;
        private final int processedCount;
        private final int inputEventCount;
        private final int executionCount;

        private PendingFlushBatch(int orderCount, int eventCount, int processedCount,
                                  int inputEventCount, int executionCount) {
            this.orderCount = orderCount;
            this.eventCount = eventCount;
            this.processedCount = processedCount;
            this.inputEventCount = inputEventCount;
            this.executionCount = executionCount;
        }

        private boolean isEmpty() {
            return orderCount == 0
                    && eventCount == 0
                    && processedCount == 0
                    && inputEventCount == 0
                    && executionCount == 0;
        }

        private void clearBuffers() {
            Arrays.fill(orderBatchBuffer, 0, orderCount, null);
            Arrays.fill(eventBatchBuffer, 0, eventCount, null);
            Arrays.fill(processedBatchBuffer, 0, processedCount, null);
            Arrays.fill(inputEventBatchBuffer, 0, inputEventCount, null);
            Arrays.fill(executionBatchBuffer, 0, executionCount, null);
        }
    }
}

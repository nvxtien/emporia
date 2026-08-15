package com.emporia.ordermanagement.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;
import com.emporia.ordermanagement.model.ProcessedCommand;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.ProcessedCommandRepository;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * In-process write-through cache for {@link TradingOrder} and
 * {@link ProcessedCommand} that removes the hot-path DB SELECTs.
 *
 * <h2>Motivation</h2>
 * <p>Every command handler call previously issued 3–5 synchronous
 * {@code SELECT} queries against PostgreSQL before touching the matching
 * engine or emitting events. On a warm JVM each round-trip costs 1–5 ms, so
 * a single {@code CREATE} command already accumulates 3–15 ms of pure DB
 * wait before any business logic runs.
 *
 * <h2>Cache contract</h2>
 * <ul>
 *   <li><b>write-through</b>: every {@code put} writes to both the cache and
 *       the repository in the same transaction scope. The DB remains the
 *       system-of-record for durability and cross-instance reads.</li>
 *   <li><b>read-through on miss</b>: a cache miss delegates to the
 *       repository. This is what {@link RotatingDedupIndex} exists to avoid: a
 *       {@code CREATE} carries freshly generated ids, so it misses every time -
 *       measured at zero hits across 14,402 orders, costing 2.312 of the
 *       handler's 2.405 ms on the single writer thread. When the index is
 *       present and ready it answers "never seen" from memory and the
 *       repository is not consulted - including for identifiers older than the
 *       index's horizon, which is why that horizon is a correctness parameter
 *       rather than a performance one.</li>
 *   <li><b>single-instance authority</b>: deduplication is correct if and only
 *       if exactly one instance accepts orders. The order path asks
 *       {@code isPrimary()} in {@code DisruptorOrderPipeline} rather than
 *       assuming it, but that answer is only worth what the elector behind it is
 *       worth, and <b>neither provider in this repository excludes a second
 *       machine</b>. See {@code CONFIGURATION.md}. HTTP read queries
 *       ({@code GET /orders}) bypass the cache entirely and go straight to the
 *       DB.</li>
 * </ul>
 *
 * <p>An earlier version of this javadoc attributed single-instance authority to
 * Kafka consumer-group partition assignment. Order intake moved in-process at
 * {@code 67eaecf} and no Kafka listener remains in this service, so that basis
 * had not existed for some time.
 *
 * <h2>Eviction</h2>
 * <ul>
 *   <li>{@code trading-orders}: 100,000 entries, expire 1 h after last write.
 *       A desk with 10,000 live orders at any moment fits comfortably.</li>
 *   <li>{@code processed-commands}: 50,000 entries, expire 24 h after write.
 *       Dedup window matches the Idempotency-Key TTL expected by callers.</li>
 * </ul>
 */
@Component
public class OrderStateCache {

    private final Cache<UUID, TradingOrder> orders;
    private final Cache<UUID, ProcessedCommand> processed;
    private final TradingOrderRepository orderRepository;
    private final ProcessedCommandRepository processedRepository;
    // Both lookups run on the Disruptor writer thread, where cost multiplies by
    // the whole queue depth rather than being absorbed in parallel. Tagged by
    // source so a run says how often the read-through fallback actually fires:
    // for a CREATE both ids are freshly generated, so it fires every time.
    private final Timer processedFromCache;
    private final Timer processedFromDb;
    private final Timer processedFromIndex;
    private final Timer existsFromCache;
    private final Timer existsFromDb;
    private final Timer existsFromIndex;
    // Owns every filter, including the rotation that stops them growing for as
    // long as the process runs, and every filter it holds has a single writer.
    //
    // Nullable for tests only. The bean is unconditional since the enabled flag
    // was removed, so this is never null at runtime - but a null here restores
    // the pure read-through behaviour, which is the seam the tests use to cover
    // the path the index replaced.
    private final @Nullable RotatingDedupIndex dedup;

    public OrderStateCache(
            TradingOrderRepository orderRepository,
            ProcessedCommandRepository processedRepository,
            OrderMetrics metrics,
            @Nullable RotatingDedupIndex dedup,
            @Value("${emporia.cache.order-max-size:100000}") long orderMaxSize,
            @Value("${emporia.cache.processed-max-size:50000}") long processedMaxSize) {
        this.orderRepository = orderRepository;
        this.processedRepository = processedRepository;
        this.dedup = dedup;
        this.processedFromIndex = metrics.registry().timer("emporia.oms.cache.lookup", "check", "processed", "source", "index");
        this.existsFromIndex = metrics.registry().timer("emporia.oms.cache.lookup", "check", "exists", "source", "index");
        this.processedFromCache = metrics.registry().timer("emporia.oms.cache.lookup", "check", "processed", "source", "cache");
        this.processedFromDb = metrics.registry().timer("emporia.oms.cache.lookup", "check", "processed", "source", "db");
        this.existsFromCache = metrics.registry().timer("emporia.oms.cache.lookup", "check", "exists", "source", "cache");
        this.existsFromDb = metrics.registry().timer("emporia.oms.cache.lookup", "check", "exists", "source", "db");
        this.orders = Caffeine.newBuilder()
                .maximumSize(orderMaxSize)
                .expireAfterWrite(Duration.ofHours(1))
                .recordStats()
                .build();
        this.processed = Caffeine.newBuilder()
                .maximumSize(processedMaxSize)
                .expireAfterWrite(Duration.ofHours(24))
                .recordStats()
                .build();
        // recordStats() has been on since these caches were written and nothing
        // read it, so how often either tier actually answers has never been
        // visible - and both are sized by argument rather than by measurement.
        // The processed tier is the one that needs the answer: 50,000 entries at
        // ~900 bytes of payload each is more memory than the whole deduplication
        // index, held to save a database read on a path only retries take, and
        // it covers by count rather than by time - about seven minutes of
        // traffic at 120 orders/sec against a client retry window of seconds.
        // cache_gets_total{result="hit"} against {result="miss"} settles it.
        CaffeineCacheMetrics.monitor(metrics.registry(), this.orders, "trading-orders");
        CaffeineCacheMetrics.monitor(metrics.registry(), this.processed, "processed-commands");
    }

    // ── TradingOrder ──────────────────────────────────────────────────────────

    /**
     * Returns the cached order, falling through to the DB on a miss.
     *
     * <p>Uses the desk-scoped query so a cross-desk lookup attempt still
     * returns {@link Optional#empty()} rather than a wrong order.
     */
    public Optional<TradingOrder> findByIdAndDeskId(UUID id, String deskId) {
        TradingOrder cached = orders.getIfPresent(id);
        if (cached != null) {
            // Verify desk ownership without a DB round-trip.
            return cached.getDeskId().equals(deskId) ? Optional.of(cached) : Optional.empty();
        }
        Optional<TradingOrder> fromDb = orderRepository.findByIdAndDeskId(id, deskId);
        fromDb.ifPresent(o -> orders.put(o.getId(), o));
        return fromDb;
    }

    /** Returns {@code true} when an order with this id exists (any desk). */
    public boolean existsById(UUID id) {
        long started = System.nanoTime();
        if (orders.getIfPresent(id) != null) {
            existsFromCache.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            return true;
        }
        if (indexAnswers() && dedup.definitelyNew(id)) {
            existsFromIndex.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            return false;
        }
        boolean exists = orderRepository.existsById(id);
        existsFromDb.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        return exists;
    }

    /**
     * Whether the index may be trusted to answer "never seen". False while the
     * startup load is still running, because a partially filled filter reports
     * "never seen" for things it has seen - see
     * {@link RotatingDedupIndex#isReady()}.
     */
    private boolean indexAnswers() {
        return dedup != null && dedup.isReady();
    }

    public boolean isReady() {
        return indexAnswers();
    }

    /**
     * Stores the order in the cache. Called after every successful
     * {@code orders.save()} so the next read hits the cache.
     */
    public void put(TradingOrder order) {
        // The one funnel every committed state change passes through, which is
        // why the revision is stamped here rather than in the mutators - see
        // TradingOrder.recordRevision. Read-through population uses the cache
        // field directly and so does not reach this method.
        order.recordRevision();
        orders.put(order.getId(), order);
        if (dedup != null) dedup.remember(order.getId());
    }

    // ── ProcessedCommand ──────────────────────────────────────────────────────

    /**
     * Returns the cached dedup result, falling through to the DB on a miss.
     *
     * <p>A DB miss means the command has not been processed before — the
     * normal case on a warm instance.
     */
    public Optional<ProcessedCommand> findProcessedById(UUID commandId) {
        long started = System.nanoTime();
        ProcessedCommand cached = processed.getIfPresent(commandId);
        if (cached != null) {
            processedFromCache.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            return Optional.of(cached);
        }
        if (indexAnswers() && dedup.definitelyNew(commandId)) {
            processedFromIndex.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            return Optional.empty();
        }
        Optional<ProcessedCommand> fromDb = processedRepository.findById(commandId);
        fromDb.ifPresent(p -> processed.put(commandId, p));
        processedFromDb.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        return fromDb;
    }

    /** Stores the dedup result in the cache after persisting it to the DB. */
    public void putProcessed(ProcessedCommand command) {
        processed.put(command.result().commandId(), command);
        // Remembered even while warming: a command processed during the load
        // must be in the index by the time the load finishes, or the flip to
        // ready would leave a hole exactly the size of the warm-up window.
        if (dedup != null) dedup.remember(command.result().commandId());
    }
}

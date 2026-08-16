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

    /**
     * How long a repeated {@code Idempotency-Key} is honoured as a retry rather
     * than treated as a new request. This is a promise made to callers, and the
     * deduplication horizon has to be at least as long or the system forgets a
     * key it said it still honours - {@code DedupIndexConfig} refuses to start
     * when it is not, which is why this is public rather than inlined below.
     */
    public static final Duration IDEMPOTENCY_KEY_TTL = Duration.ofHours(24);

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

    /** Live orders admitted before new ones are refused. See {@link #atCapacity()}. */
    private final long liveOrderMax;

    /** See {@link #isLiveSetComplete()}. Written once, by the startup load. */
    private volatile boolean liveSetComplete;

    public OrderStateCache(
            TradingOrderRepository orderRepository,
            ProcessedCommandRepository processedRepository,
            OrderMetrics metrics,
            @Nullable RotatingDedupIndex dedup,
            @Value("${emporia.orders.live-max:1000000}") long liveOrderMax,
            @Value("${emporia.cache.processed-max-size:50000}") long processedMaxSize) {
        this.liveOrderMax = liveOrderMax;
        this.orderRepository = orderRepository;
        this.processedRepository = processedRepository;
        this.dedup = dedup;
        this.processedFromIndex = metrics.registry().timer("emporia.oms.cache.lookup", "check", "processed", "source", "index");
        this.existsFromIndex = metrics.registry().timer("emporia.oms.cache.lookup", "check", "exists", "source", "index");
        this.processedFromCache = metrics.registry().timer("emporia.oms.cache.lookup", "check", "processed", "source", "cache");
        this.processedFromDb = metrics.registry().timer("emporia.oms.cache.lookup", "check", "processed", "source", "db");
        this.existsFromCache = metrics.registry().timer("emporia.oms.cache.lookup", "check", "exists", "source", "cache");
        this.existsFromDb = metrics.registry().timer("emporia.oms.cache.lookup", "check", "exists", "source", "db");
        // No maximumSize and no expireAfterWrite: this is a live-order store, not
        // a cache. Both of those evicted orders that were still live - the size
        // bound silently (137,435 live orders were observed against a 100,000
        // default) and the one-hour expiry on every order that rested longer
        // than an hour. Each eviction turned a later lookup into a blocking
        // database read on the single writer, and made any index over this map
        // answer from whatever happened to survive.
        //
        // What bounds it now is liveness: put() removes an order when it
        // reaches a terminal status, and liveOrderMax refuses new orders rather
        // than dropping existing ones. Refusing is visible; evicting a live
        // order is not.
        this.orders = Caffeine.newBuilder()
                .recordStats()
                .build();
        this.processed = Caffeine.newBuilder()
                .maximumSize(processedMaxSize)
                .expireAfterWrite(IDEMPOTENCY_KEY_TTL)
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
        // Only live orders are admitted: a terminal order loaded here is being
        // read on an error path and must not take a slot in the live store.
        fromDb.filter(o -> !isTerminal(o.getStatus())).ifPresent(o -> orders.put(o.getId(), o));
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
        if (isTerminal(order.getStatus())) {
            // A finished order is not live, and this store holds live orders.
            // Leaving it here is how the store filled with orders nothing would
            // ever act on again. It stays answerable through the read-through
            // fallback, which is what lets a cancel of a filled order say 409
            // rather than 404.
            orders.invalidate(order.getId());
        } else {
            orders.put(order.getId(), order);
        }
        // Remembered either way: the deduplication index is about identifiers
        // ever seen, not about orders still live.
        if (dedup != null) dedup.remember(order.getId());
    }

    /** Orders currently live in memory. */
    public long liveOrderCount() {
        return orders.estimatedSize();
    }

    /**
     * Whether the live-order store holds <b>every</b> live order, not merely the
     * ones this process has seen since it started.
     *
     * <p>Nothing may answer a question negatively from this store until it is
     * true. An index over a partially loaded store reports the orders that
     * happened to be in it, which is the same defect the bounded cache had and
     * the reason this store exists.
     */
    public boolean isLiveSetComplete() {
        return liveSetComplete;
    }

    /**
     * Admits an order loaded from the database during startup.
     *
     * <p>Deliberately not {@link #put}: that stamps a revision, and replaying
     * existing rows into memory is not a state change. Terminal orders are
     * skipped rather than rejected, so the caller may hand over whatever the
     * query returned.
     */
    void admitExisting(TradingOrder order) {
        if (!isTerminal(order.getStatus())) {
            orders.put(order.getId(), order);
        }
    }

    /**
     * Declares the startup load complete. Called only after every live order has
     * been admitted - never after a partial load, for the reason on
     * {@link #isLiveSetComplete()}.
     */
    void markLiveSetComplete() {
        this.liveSetComplete = true;
    }

    /**
     * Whether the live-order store is full.
     *
     * <p>Refusing a new order is the correct answer here and evicting an
     * existing one is not: an evicted live order is one this service can no
     * longer see, answer for, or cancel, and nothing would say so. The ring
     * already makes the same trade when it fills, and answers 429.
     */
    public boolean atCapacity() {
        return liveOrderCount() >= liveOrderMax;
    }

    private static boolean isTerminal(com.emporia.events.TradingEvents.OrderStatus status) {
        return status == com.emporia.events.TradingEvents.OrderStatus.FILLED
                || status == com.emporia.events.TradingEvents.OrderStatus.CANCELLED
                || status == com.emporia.events.TradingEvents.OrderStatus.REJECTED;
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

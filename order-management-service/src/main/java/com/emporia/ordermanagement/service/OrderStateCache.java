package com.emporia.ordermanagement.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Timer;
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
 *       repository, so cold starts and post-restart recovery work without any
 *       special warm-up logic.</li>
 *   <li><b>single-instance authority</b>: the Kafka consumer group assigns
 *       each partition to exactly one OMS instance. Command handlers for a
 *       given order always run on the same instance, so the cache is
 *       authoritative for write-path reads. HTTP read queries ({@code GET
 *       /orders}) bypass the cache entirely and go straight to the DB.</li>
 * </ul>
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
    private final Timer existsFromCache;
    private final Timer existsFromDb;

    public OrderStateCache(
            TradingOrderRepository orderRepository,
            ProcessedCommandRepository processedRepository,
            OrderMetrics metrics,
            @Value("${emporia.cache.order-max-size:100000}") long orderMaxSize,
            @Value("${emporia.cache.processed-max-size:50000}") long processedMaxSize) {
        this.orderRepository = orderRepository;
        this.processedRepository = processedRepository;
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
        boolean exists = orderRepository.existsById(id);
        existsFromDb.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        return exists;
    }

    /**
     * Stores the order in the cache. Called after every successful
     * {@code orders.save()} so the next read hits the cache.
     */
    public void put(TradingOrder order) {
        orders.put(order.getId(), order);
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
        Optional<ProcessedCommand> fromDb = processedRepository.findById(commandId);
        fromDb.ifPresent(p -> processed.put(commandId, p));
        processedFromDb.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        return fromDb;
    }

    /** Stores the dedup result in the cache after persisting it to the DB. */
    public void putProcessed(ProcessedCommand command) {
        processed.put(command.result().commandId(), command);
    }
}

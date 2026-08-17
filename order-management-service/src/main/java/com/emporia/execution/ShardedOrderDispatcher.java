package com.emporia.execution;

import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.ordermanagement.service.OrderStreamService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Sharded in-process dispatcher for order domain events, replacing the Kafka
 * hop between order intake and execution routing. Partitions events across a
 * fixed array of single-threaded shard executors based on order ID hash,
 * guaranteeing strict in-order processing per order without a broker
 * round-trip or lock contention.
 */
@Component
public class ShardedOrderDispatcher {
    private static final Logger log = LoggerFactory.getLogger(ShardedOrderDispatcher.class);

    private final int numShards;
    private final ExecutorService[] shards;
    // The same array, typed so the queue can be read. Executors.newSingleThreadExecutor
    // hands back an unbounded LinkedBlockingQueue wrapped so its depth is invisible,
    // and this queue sits between "the client was told 201" and "the venue has the
    // order" - the one place a backlog is both possible and unobservable.
    private final ThreadPoolExecutor[] queues;
    private final ExecutionEventConsumer eventConsumer;
    private final OrderStreamService streams;

    // PMD's CloseResource can't see that these pools outlive the constructor
    // and are closed in shutdown() (already suppressed there), so it flags the
    // creation site here too.
    @SuppressWarnings("PMD.CloseResource")
    public ShardedOrderDispatcher(
            @Value("${emporia.execution.dispatcher.shards:8}") int numShards,
            @Lazy ExecutionEventConsumer eventConsumer,
            OrderStreamService streams,
            MeterRegistry meters) {
        this.numShards = Math.max(1, numShards);
        this.eventConsumer = Objects.requireNonNull(eventConsumer, "eventConsumer");
        this.streams = Objects.requireNonNull(streams, "streams");

        log.info("Sharded In-Process Dispatcher initialized (shards={})", this.numShards);
        this.shards = new ExecutorService[this.numShards];
        this.queues = new ThreadPoolExecutor[this.numShards];
        for (int i = 0; i < this.numShards; i++) {
            final int shardIndex = i;
            // newSingleThreadExecutor wraps a ThreadPoolExecutor in a finalizable
            // delegate, so build the same thing directly rather than unwrapping it.
            ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(),
                    runnable -> {
                        Thread thread = new Thread(runnable, "execution-dispatcher-shard-" + shardIndex);
                        thread.setDaemon(true);
                        return thread;
                    });
            this.shards[i] = pool;
            this.queues[i] = pool;
        }
        registerQueueGauge(meters);
    }

    private void registerQueueGauge(MeterRegistry meters) {
        // The gap between "the client has a 201" and "the venue has the order".
        // A backlog here is invisible in every other signal: order-management
        // looks healthy, the venue looks healthy, and only the two together are
        // short. It is also unbounded, so it can absorb an arbitrary amount of
        // work before anything says so.
        Gauge.builder("emporia.oms.dispatcher.queue.depth", this, ShardedOrderDispatcher::queueDepth)
                .description("Order events accepted but not yet handed to the venue")
                .register(meters);
    }

    /** Events accepted by order-management and not yet handed to the venue. */
    @SuppressWarnings("PMD.CloseResource")
    public int queueDepth() {
        int depth = 0;
        for (ThreadPoolExecutor pool : queues) {
            depth += pool.getQueue().size();
        }
        return depth;
    }

    public int getNumShards() {
        return numShards;
    }

    public void dispatch(OrderDomainEvent event) {
        if (event == null || event.orderId() == null) {
            return;
        }
        int shardIndex = Math.abs(event.orderId().hashCode()) % numShards;
        shards[shardIndex].submit(() -> {
            try {
                eventConsumer.processEvent(event);
            } catch (Exception exception) {
                log.error("Failed to process order domain event in-process for order {}", event.orderId(), exception);
            }
            try {
                // Replaces the deleted OrderDomainEventStreamConsumer's Kafka listener: same
                // event, same downstream call, just triggered directly instead of via a
                // permanently-rebalancing ephemeral consumer group. Runs on the shard thread,
                // not the caller's, so a slow/backpressured SSE client can't add latency to
                // order submission.
                streams.publish(event);
            } catch (Exception exception) {
                log.warn("Failed to publish order domain event to SSE stream for order {}", event.orderId(), exception);
            }
        });
    }

    @PreDestroy
    @SuppressWarnings("PMD.CloseResource")
    public void shutdown() {
        for (ExecutorService shard : shards) {
            shard.shutdown();
            try {
                if (!shard.awaitTermination(2, TimeUnit.SECONDS)) {
                    shard.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                shard.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}

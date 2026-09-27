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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ThreadPoolExecutor;
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
    private final ThreadPoolExecutor streamPublisher;
    // The same array, typed so queue depth is observable. Capacity is bounded so
    // overload becomes a retryable outbox failure instead of unbounded memory use.
    private final ThreadPoolExecutor[] queues;
    private final ExecutionEventConsumer eventConsumer;
    private final OrderStreamService streams;
    private final io.micrometer.core.instrument.Counter rejected;
    private final io.micrometer.core.instrument.Counter streamRejected;

    // PMD's CloseResource can't see that these pools outlive the constructor
    // and are closed in shutdown() (already suppressed there), so it flags the
    // creation site here too.
    @SuppressWarnings("PMD.CloseResource")
    public ShardedOrderDispatcher(
            @Value("${emporia.execution.dispatcher.shards:8}") int numShards,
            @Lazy ExecutionEventConsumer eventConsumer,
            OrderStreamService streams,
            MeterRegistry meters) {
        this(numShards, 4096, 1024, eventConsumer, streams, meters);
    }

    @SuppressWarnings("PMD.CloseResource")
    public ShardedOrderDispatcher(int numShards, int queueCapacity,
                                  ExecutionEventConsumer eventConsumer,
                                  OrderStreamService streams, MeterRegistry meters) {
        this(numShards, queueCapacity, 1024, eventConsumer, streams, meters);
    }

    @org.springframework.beans.factory.annotation.Autowired
    @SuppressWarnings("PMD.CloseResource")
    public ShardedOrderDispatcher(
            @Value("${emporia.execution.dispatcher.shards:8}") int numShards,
            @Value("${emporia.execution.dispatcher.queue-capacity:4096}") int queueCapacity,
            @Value("${emporia.execution.stream.queue-capacity:1024}") int streamQueueCapacity,
            @Lazy ExecutionEventConsumer eventConsumer,
            OrderStreamService streams,
            MeterRegistry meters) {
        this.numShards = Math.max(1, numShards);
        int boundedCapacity = Math.max(1, queueCapacity);
        int boundedStreamCapacity = Math.max(1, streamQueueCapacity);
        this.eventConsumer = Objects.requireNonNull(eventConsumer, "eventConsumer");
        this.streams = Objects.requireNonNull(streams, "streams");
        this.rejected = meters.counter("emporia.oms.dispatcher.rejected");
        this.streamRejected = meters.counter("emporia.oms.sse.rejected");
        this.shards = new ExecutorService[this.numShards];
        this.queues = new ThreadPoolExecutor[this.numShards];
        this.streamPublisher = new ThreadPoolExecutor(1, 1, 0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(boundedStreamCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "order-stream-publisher-0");
                    thread.setDaemon(true);
                    return thread;
                });
        for (int i = 0; i < this.numShards; i++) {
            final int shardIndex = i;
            ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(boundedCapacity),
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
        // short. The bounded queue rejects excess work so the durable outbox can retry it.
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

    public CompletableFuture<Void> dispatch(OrderDomainEvent event) {
        if (event == null || event.orderId() == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> completion = new CompletableFuture<>();
        int shardIndex = Math.floorMod(event.orderId().hashCode(), numShards);
        try {
            shards[shardIndex].submit(() -> {
                try {
                    eventConsumer.processEvent(event);
                } catch (Exception exception) {
                    log.error("Failed to process order domain event in-process for order {}", event.orderId(), exception);
                    completion.completeExceptionally(exception);
                    return;
                }
                try {
                    streamPublisher.execute(() -> {
                        try {
                            streams.publish(event);
                        } catch (Exception exception) {
                            log.warn("Failed to publish order domain event to SSE stream for order {}",
                                    event.orderId(), exception);
                        }
                    });
                } catch (RejectedExecutionException streamBackpressure) {
                    streamRejected.increment();
                    log.warn("Dropped SSE event for order {} because the stream queue is full",
                            event.orderId());
                }
                completion.complete(null);
            });
        } catch (RejectedExecutionException rejectedExecution) {
            rejected.increment();
            completion.completeExceptionally(rejectedExecution);
            log.warn("Rejected order domain event for order {} because the dispatcher queue is full",
                    event.orderId());
        }
        return completion;
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
        streamPublisher.shutdown();
        try {
            if (!streamPublisher.awaitTermination(2, TimeUnit.SECONDS)) {
                streamPublisher.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            streamPublisher.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

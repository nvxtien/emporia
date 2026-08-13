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
    private final ExecutionEventConsumer eventConsumer;
    private final OrderStreamService streams;

    public ShardedOrderDispatcher(
            @Value("${emporia.execution.dispatcher.shards:8}") int numShards,
            @Lazy ExecutionEventConsumer eventConsumer,
            OrderStreamService streams) {
        this.numShards = Math.max(1, numShards);
        this.eventConsumer = Objects.requireNonNull(eventConsumer, "eventConsumer");
        this.streams = Objects.requireNonNull(streams, "streams");

        log.info("Sharded In-Process Dispatcher initialized (shards={})", this.numShards);
        this.shards = new ExecutorService[this.numShards];
        for (int i = 0; i < this.numShards; i++) {
            final int shardIndex = i;
            this.shards[i] = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "execution-dispatcher-shard-" + shardIndex);
                thread.setDaemon(true);
                return thread;
            });
        }
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

package com.emporia.execution;

import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.ordermanagement.service.OrderStreamService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class ShardedOrderDispatcherTest {

    @Test
    void routesDispatchedEventsToAShardWorker() throws Exception {
        ExecutionEventConsumer consumer = mock(ExecutionEventConsumer.class);
        OrderStreamService streams = mock(OrderStreamService.class);
        ShardedOrderDispatcher dispatcher = new ShardedOrderDispatcher(4, consumer, streams, new SimpleMeterRegistry());

        assertThat(dispatcher.getNumShards()).isEqualTo(4);

        UUID orderId = UUID.randomUUID();
        OrderDomainEvent event = new OrderDomainEvent(
                1, UUID.randomUUID(), UUID.randomUUID(), orderId,
                "user1", "desk1", "CREATED", 1L, OrderStatus.LIVE, Instant.now(), "{}"
        );

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();

        doAnswer(invocation -> {
            threadName.set(Thread.currentThread().getName());
            latch.countDown();
            return null;
        }).when(consumer).processEvent(any());

        dispatcher.dispatch(event);

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(threadName.get()).startsWith("execution-dispatcher-shard-");

        dispatcher.shutdown();
    }

    @Test
    void defaultsToEightShardsWhenNotConfigured() {
        ExecutionEventConsumer consumer = mock(ExecutionEventConsumer.class);
        OrderStreamService streams = mock(OrderStreamService.class);
        ShardedOrderDispatcher dispatcher = new ShardedOrderDispatcher(8, consumer, streams, new SimpleMeterRegistry());

        assertThat(dispatcher.getNumShards()).isEqualTo(8);

        dispatcher.shutdown();
    }
}

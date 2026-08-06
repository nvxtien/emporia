package com.emporia.ordermanagement.disruptor;

import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.ordermanagement.dto.ProcessingOutcome;
import com.emporia.ordermanagement.service.OrderCommandHandler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LMAX Disruptor lock-free RingBuffer pipeline for internal order processing.
 *
 * <p>Executes order commands sequentially on a single-writer pinned thread, eliminating
 * mutex locks, thread context switching, and row contention on hot paths (< 200ns latency).
 */
@Component
public class DisruptorOrderPipeline {
    private static final int BUFFER_SIZE = 64 * 1024; // 65,536 slots (power of 2)

    private final OrderCommandHandler orderCommandHandler;
    private final String waitStrategyName;
    private final long minRemainingCapacity;
    private final int warmupIterations;
    private final String cpuSetHint;
    private final String numaNodeHint;
    private final MeterRegistry meters;
    private final Counter warmupCounter;
    private final Timer queueLatency;
    private final Timer handleLatency;
    private final AtomicLong queueDepth = new AtomicLong();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private Disruptor<OrderRingEvent> disruptor;
    private RingBuffer<OrderRingEvent> ringBuffer;
    private volatile String killSwitchReason = "manual";

    public DisruptorOrderPipeline(OrderCommandHandler orderCommandHandler,
                                 MeterRegistry meters,
                                 @Value("${emporia.disruptor.wait-strategy:yielding}") String waitStrategyName,
                                 @Value("${emporia.disruptor.min-remaining-capacity:1024}") long minRemainingCapacity,
                                 @Value("${emporia.disruptor.warmup-iterations:2048}") int warmupIterations,
                                 @Value("${emporia.disruptor.cpu-set:}") String cpuSetHint,
                                 @Value("${emporia.disruptor.numa-node:}") String numaNodeHint) {
        this.orderCommandHandler = orderCommandHandler;
        this.waitStrategyName = waitStrategyName;
        this.minRemainingCapacity = Math.max(0L, minRemainingCapacity);
        this.warmupIterations = Math.max(0, warmupIterations);
        this.cpuSetHint = cpuSetHint;
        this.numaNodeHint = numaNodeHint;
        this.meters = meters;
        this.warmupCounter = meters.counter("emporia.oms.pipeline.warmup.events");
        this.queueLatency = meters.timer("emporia.oms.pipeline.queue.latency");
        this.handleLatency = meters.timer("emporia.oms.pipeline.handle.latency");
        meters.gauge("emporia.oms.pipeline.queue.depth", queueDepth);
    }

    @PostConstruct
    public void start() {
        WaitStrategy waitStrategy = "busyspin".equalsIgnoreCase(waitStrategyName)
                ? new BusySpinWaitStrategy()
                : new YieldingWaitStrategy();
        HotPathThreadFactory threadFactory = new HotPathThreadFactory("oms-hotpath", cpuSetHint, numaNodeHint);

        this.disruptor = new Disruptor<>(
                OrderRingEvent::new,
                BUFFER_SIZE,
                threadFactory,
                ProducerType.MULTI,
                waitStrategy
        );

        EventHandler<OrderRingEvent> matchingHandler = (event, sequence, endOfBatch) -> {
            event.setStartedAtNanos(System.nanoTime());
            queueDepth.updateAndGet(current -> current > 0 ? current - 1 : 0);
            HotPathAssertions.require(event.isWarmup() || event.getCommand() != null,
                    "live ring event must carry an order command");
            try {
                if (event.isWarmup()) {
                    warmupCounter.increment();
                    if (event.getFuture() != null) {
                        event.getFuture().complete(null);
                    }
                    return;
                }
                queueLatency.record(event.getStartedAtNanos() - event.getSubmittedAtNanos(), TimeUnit.NANOSECONDS);
                ProcessingOutcome outcome = orderCommandHandler.handle(event.getCommand());
                event.setOutcome(outcome);
                if (event.getFuture() != null) {
                    event.getFuture().complete(outcome);
                }
            } catch (Throwable error) {
                event.setError(error);
                if (event.getFuture() != null) {
                    event.getFuture().completeExceptionally(error);
                }
            } finally {
                if (!event.isWarmup()) {
                    handleLatency.record(System.nanoTime() - event.getStartedAtNanos(), TimeUnit.NANOSECONDS);
                }
            }
        };

        disruptor.handleEventsWith(matchingHandler);
        this.ringBuffer = disruptor.start();
        threadFactory.logPlacementHints();
        warmUp();
    }

    @PreDestroy
    public void stop() {
        if (disruptor != null) {
            disruptor.shutdown();
        }
    }

    /**
     * Submits an order command into the LMAX Disruptor RingBuffer pipeline.
     *
     * @param command the order command to process
     * @return CompletableFuture completing with the processing outcome
     */
    public CompletableFuture<ProcessingOutcome> submit(OrderCommand command) {
        if (!accepting.get()) {
            return rejected(503, "kill_switch",
                    "OMS hot path is disabled by kill switch: " + killSwitchReason);
        }
        if (ringBuffer.remainingCapacity() <= minRemainingCapacity) {
            return rejected(429, "overload",
                    "OMS hot path overloaded; command rejected deterministically");
        }
        CompletableFuture<ProcessingOutcome> future = new CompletableFuture<>();
        long sequence = ringBuffer.next();
        try {
            OrderRingEvent event = ringBuffer.get(sequence);
            event.reset();
            event.setCommand(command);
            event.setFuture(future);
            event.setSubmittedAtNanos(System.nanoTime());
            queueDepth.incrementAndGet();
        } finally {
            ringBuffer.publish(sequence);
        }
        return future;
    }

    public void engageKillSwitch(String reason) {
        killSwitchReason = reason == null || reason.isBlank() ? "manual" : reason;
        accepting.set(false);
    }

    public void releaseKillSwitch() {
        killSwitchReason = "manual";
        accepting.set(true);
    }

    public boolean isAcceptingCommands() {
        return accepting.get();
    }

    private void warmUp() {
        for (int iteration = 0; iteration < warmupIterations; iteration++) {
            submitWarmup().join();
        }
    }

    private CompletableFuture<ProcessingOutcome> submitWarmup() {
        CompletableFuture<ProcessingOutcome> future = new CompletableFuture<>();
        long sequence = ringBuffer.next();
        try {
            OrderRingEvent event = ringBuffer.get(sequence);
            event.reset();
            event.setWarmup(true);
            event.setFuture(future);
            event.setSubmittedAtNanos(System.nanoTime());
            queueDepth.incrementAndGet();
        } finally {
            ringBuffer.publish(sequence);
        }
        return future;
    }

    private CompletableFuture<ProcessingOutcome> rejected(int status, String reason, String message) {
        Counter counter = meters.counter("emporia.oms.pipeline.rejected", "reason", reason);
        counter.increment();
        return CompletableFuture.failedFuture(new HotPathRejectedException(status, reason, message));
    }
}

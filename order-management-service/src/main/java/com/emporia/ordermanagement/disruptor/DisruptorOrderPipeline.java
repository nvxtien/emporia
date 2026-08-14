package com.emporia.ordermanagement.disruptor;

import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.ordermanagement.dto.ProcessingOutcome;
import com.emporia.ordermanagement.service.OrderCommandHandler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.emporia.events.sbe.SbeEncoderDecoder;
import com.emporia.ordermanagement.service.MemoryMappedWalLogger;
import com.emporia.ordermanagement.service.OrderCommandReplayHarness;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.emporia.ha.LeaderElectionService;
import org.jspecify.annotations.Nullable;
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
    private static final Logger log = LoggerFactory.getLogger(DisruptorOrderPipeline.class);
    private static final int BUFFER_SIZE = 64 * 1024; // 65,536 slots (power of 2)

    private final OrderCommandHandler orderCommandHandler;
    private final MemoryMappedWalLogger wal;
    /** Null only in tests that construct the pipeline without recovery. */
    private final OrderCommandReplayHarness replayHarness;
    // Null when no leader election is wired, which accepts orders as before.
    // Order deduplication is only correct while exactly one instance accepts
    // orders, and nothing on this path used to check that: the javadoc on
    // OrderStateCache claimed Kafka consumer-group partition assignment
    // provided it, but intake moved in-process at 67eaecf and there are no
    // Kafka listeners left. Enforced here so a second instance fails loudly
    // rather than silently accepting duplicate orders.
    private final @Nullable LeaderElectionService leaderElection;
    private final Counter walFailures;
    private final String waitStrategyName;
    private final long minRemainingCapacity;
    private final int warmupIterations;
    private final String cpuSetHint;
    /**
     * Above this queue wait, one line is written describing what the writer
     * thread was doing. {@code Long.MAX_VALUE} when disabled, so the check is a
     * single comparison on the hot path.
     */
    private final long stallThresholdNanos;
    private final String numaNodeHint;
    private final MeterRegistry meters;
    private final Counter warmupCounter;
    private final Timer queueLatency;
    /**
     * Splits the writer thread's own work. Everything here runs on the single
     * writer, so anything but state mutation is time no other command can use.
     */
    private final Timer walLatency;
    private final Timer commandLatency;
    private final Timer safePointLatency;
    /**
     * Gap between consecutive publishes. Queue wait is (events ahead) x handler
     * time, so whether arrivals are smooth or bursty decides how deep the ring
     * gets - and that cannot be inferred from the wait itself.
     */
    private final Timer arrivalGap;
    private final java.util.concurrent.atomic.AtomicLong lastSubmitNanos =
            new java.util.concurrent.atomic.AtomicLong();
    private final Timer handleLatency;
    private final AtomicLong queueDepth = new AtomicLong();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private Disruptor<OrderRingEvent> disruptor;
    private RingBuffer<OrderRingEvent> ringBuffer;
    private volatile String killSwitchReason = "manual";

    public DisruptorOrderPipeline(OrderCommandHandler orderCommandHandler,
                                 MeterRegistry meters,
                                 MemoryMappedWalLogger wal,
                                 OrderCommandReplayHarness replayHarness,
                                 @Nullable LeaderElectionService leaderElection,
                                 @Value("${emporia.disruptor.wait-strategy:yielding}") String waitStrategyName,
                                 @Value("${emporia.disruptor.min-remaining-capacity:1024}") long minRemainingCapacity,
                                 @Value("${emporia.disruptor.warmup-iterations:2048}") int warmupIterations,
                                 @Value("${emporia.disruptor.stall-threshold-ms:0}") long stallThresholdMs,
                                 @Value("${emporia.disruptor.cpu-set:}") String cpuSetHint,
                                 @Value("${emporia.disruptor.numa-node:}") String numaNodeHint) {
        this.orderCommandHandler = orderCommandHandler;
        this.wal = wal;
        this.replayHarness = replayHarness;
        this.leaderElection = leaderElection;
        this.waitStrategyName = waitStrategyName;
        this.minRemainingCapacity = Math.max(0L, minRemainingCapacity);
        this.warmupIterations = Math.max(0, warmupIterations);
        this.stallThresholdNanos = stallThresholdMs <= 0
                ? Long.MAX_VALUE
                : TimeUnit.MILLISECONDS.toNanos(stallThresholdMs);
        this.cpuSetHint = cpuSetHint;
        this.numaNodeHint = numaNodeHint;
        this.meters = meters;
        this.warmupCounter = meters.counter("emporia.oms.pipeline.warmup.events");
        this.queueLatency = meters.timer("emporia.oms.pipeline.queue.latency");
        this.walLatency = meters.timer("emporia.oms.pipeline.wal.latency");
        this.commandLatency = meters.timer("emporia.oms.pipeline.command.latency");
        this.safePointLatency = meters.timer("emporia.oms.pipeline.safepoint.latency");
        this.arrivalGap = meters.timer("emporia.oms.pipeline.arrival.gap");
        this.handleLatency = meters.timer("emporia.oms.pipeline.handle.latency");
        this.walFailures = meters.counter("emporia.oms.pipeline.wal.failures");
        meters.gauge("emporia.oms.pipeline.queue.depth", queueDepth);
    }

    @PostConstruct
    public void start() {
        // Before the ring exists, and so before anything appends. A fresh
        // mapping writes from the beginning of the file, so replaying later
        // would read records the process had already started overwriting.
        //
        // Replay goes straight to the handler rather than through the ring:
        // routed back through it, every recovered command would be appended to
        // the log a second time.
        // Never fatal. A record this build cannot replay must not leave the
        // service unable to start: that turns losing one order into losing the
        // ability to accept any, and the log would keep failing the same way on
        // every restart.
        if (replayHarness != null) {
            try {
                replayHarness.replayWriteAheadLog();
            } catch (RuntimeException recoveryFailure) {
                log.error("Write-ahead log recovery failed; starting without it. "
                        + "Orders accepted but unwritten before the last stop are lost",
                        recoveryFailure);
            }
        }

        WaitStrategy waitStrategy = switch (waitStrategyName.toLowerCase()) {
            case "busyspin" -> new BusySpinWaitStrategy();
            case "sleeping" -> new com.lmax.disruptor.SleepingWaitStrategy();
            case "blocking" -> new com.lmax.disruptor.BlockingWaitStrategy();
            case "lite-blocking" -> new com.lmax.disruptor.LiteBlockingWaitStrategy();
            default -> new YieldingWaitStrategy();
        };
        HotPathThreadFactory threadFactory = new HotPathThreadFactory("oms-hotpath", cpuSetHint, numaNodeHint);

        this.disruptor = new Disruptor<>(
                OrderRingEvent::new,
                BUFFER_SIZE,
                threadFactory,
                ProducerType.MULTI,
                waitStrategy
        );

        // Distinguishes the two things a long queue wait can mean. If the
        // writer was busy clearing a backlog, idleNanos is near zero and its CPU
        // time advanced across the gap. If it was simply not run - descheduled,
        // or stopped at a safepoint - idleNanos is the size of the stall and the
        // CPU time barely moved. Inferring this from p99 alone was not possible.
        log.info("Ring stall diagnostic threshold: {} (Long.MAX_VALUE means disabled)",
                stallThresholdNanos);
        final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        final long[] lastFinishedNanos = {System.nanoTime()};
        final long[] lastCpuNanos = {0L};

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
                long queueWaitNanos = event.getStartedAtNanos() - event.getSubmittedAtNanos();
                queueLatency.record(queueWaitNanos, TimeUnit.NANOSECONDS);
                if (queueWaitNanos > stallThresholdNanos) {
                    long cpuNanos = threads.getCurrentThreadCpuTime();
                    long idleNanos = event.getStartedAtNanos() - lastFinishedNanos[0];
                    long cpuUsedNanos = cpuNanos - lastCpuNanos[0];
                    // order_id is what makes this line joinable to the
                    // emporia.order.submit span, which carries the same field:
                    // the span says which order was slow, this says why.
                    log.warn("Ring queue wait {} ms for order {}: writer idle {} ms before this "
                                    + "event, using {} ms CPU across that gap, ring depth {}, "
                                    + "batch end {}. Idle time it did not spend on CPU is time it "
                                    + "was not run; near-zero idle with a deep ring is a backlog.",
                            TimeUnit.NANOSECONDS.toMillis(queueWaitNanos),
                            event.getCommand().orderId(),
                            TimeUnit.NANOSECONDS.toMillis(idleNanos),
                            TimeUnit.NANOSECONDS.toMillis(cpuUsedNanos),
                            queueDepth.get(),
                            endOfBatch);
                }
                long walStart = System.nanoTime();
                logAhead(event.getCommand());
                long commandStart = System.nanoTime();
                walLatency.record(commandStart - walStart, TimeUnit.NANOSECONDS);

                ProcessingOutcome outcome = orderCommandHandler.handle(event.getCommand());
                long safePointStart = System.nanoTime();
                commandLatency.record(safePointStart - commandStart, TimeUnit.NANOSECONDS);

                // Handled, so its rows are queued for the writer; the log space
                // up to here is reclaimable once a flush persists them.
                wal.markSafePoint();
                safePointLatency.record(System.nanoTime() - safePointStart, TimeUnit.NANOSECONDS);
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
                if (stallThresholdNanos != Long.MAX_VALUE) {
                    lastFinishedNanos[0] = System.nanoTime();
                    lastCpuNanos[0] = threads.getCurrentThreadCpuTime();
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
        long nowNanos = System.nanoTime();
        long previousNanos = lastSubmitNanos.getAndSet(nowNanos);
        if (previousNanos != 0L && nowNanos > previousNanos) {
            arrivalGap.record(nowNanos - previousNanos, TimeUnit.NANOSECONDS);
        }
        if (!accepting.get()) {
            return rejected(503, "kill_switch",
                    "OMS hot path is disabled by kill switch: " + killSwitchReason);
        }
        if (leaderElection != null && !leaderElection.isPrimary()) {
            return rejected(503, "not_primary",
                    "This instance is not the primary and does not accept orders");
        }
        if (ringBuffer.remainingCapacity() <= minRemainingCapacity) {
            return rejected(429, "overload",
                    "OMS hot path overloaded; command rejected deterministically");
        }
        CompletableFuture<ProcessingOutcome> future = new CompletableFuture<>();
        long sequence;
        try {
            sequence = ringBuffer.tryNext();
        } catch (com.lmax.disruptor.InsufficientCapacityException e) {
            return rejected(429, "overload",
                    "OMS hot path overloaded; command rejected deterministically");
        }
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

    /**
     * Records the command before it is applied, so a crash between accepting it
     * and AsyncDbWriter flushing it can be recovered.
     *
     * <p>Write-ahead in the literal sense: it runs before the handler, because a
     * record written afterwards cannot describe a command the process died
     * during. It runs on the ring's single consumer thread, so the logger's
     * synchronized append is uncontended.
     *
     * <p>Fails the command when the log is enabled but cannot take the record.
     * Continuing would accept an order the system has no durable trace of while
     * telling the caller it succeeded - the same silent loss the log exists to
     * prevent. With the log rewound on every flush this should not occur outside
     * a genuine fault.
     */
    private void logAhead(OrderCommand command) {
        if (!wal.isEnabled()) return;
        byte[] record;
        try {
            record = SbeEncoderDecoder.encodeOrderCommand(command);
        } catch (RuntimeException serialisationFailure) {
            walFailures.increment();
            throw new HotPathRejectedException(500, "wal_encode",
                    "Order could not be recorded before processing: " + serialisationFailure.getMessage(),
                    serialisationFailure);
        }
        if (!wal.append(record)) {
            walFailures.increment();
            throw new HotPathRejectedException(507, "wal_full",
                    "Order could not be recorded before processing; the write-ahead log rejected it");
        }
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

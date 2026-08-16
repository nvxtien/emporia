package com.emporia.execution.benchmark;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.CoreWaitStrategy;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.dma.DmaLimitOrder;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.simulation.ProductionSimulation;
import exchange.core2.core.simulation.ProductionSimulationAccounting;
import exchange.core2.core.simulation.ProductionSimulationConfiguration;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Measures exchange-core's matching engine on its own.
 *
 * <h2>Why this exists</h2>
 * <p>Every throughput figure this project has - 200/s, 250/s, the p99 tails - is
 * the HTTP path wrapped around the engine: gateway, authentication, the OMS
 * single-writer ring, the write-ahead log, the batched database write. None of
 * it measures matching. Asked whether the matching engine is efficient, the
 * honest answer has been that nobody knows, because
 * {@code scripts/perf/exchange-core-benchmark.sh} is a correctness smoke test
 * and says so in its own header.
 *
 * <p>The order path evidence in fact points away from matching: the ring queue
 * latency measured there is <b>upstream</b> of the venue entirely -
 * {@code OrderCommandHandler} never touches a venue gateway, which is called
 * from dispatcher shard threads - and the writer thread's own 3.047 ms is 99.4%
 * {@code OrderCommandHandler.handle}. This benchmark exists to replace that
 * inference with a number.
 *
 * <h2>What the journalling parameter separates</h2>
 * <p>{@code ProductionSimulationConfiguration}'s last argument decides how the
 * engine becomes durable, and the two settings are not a tuning choice:
 * {@code true} runs the journal as a parallel Disruptor stage, {@code false}
 * snapshots after <b>every command</b>. Running both is the point - it splits
 * "how fast does it match" from "how fast can it persist", which is the
 * distinction the p99 investigation kept running into. Checkpoint age moving
 * from 3 to 35 seconds under memory pressure, while p99 went to 388 ms with
 * most of it outside the ring, was an I/O symptom rather than a matching one.
 *
 * <h2>Reading the result honestly</h2>
 * <p>Matching-only accounting: no risk engine, no portfolio gateway, no user
 * onboarding. So this is an upper bound on the engine, not a figure for
 * Emporia's configured venue, which runs {@code full-equity-risk}. It is also a
 * single machine under whatever else is running on it - the same caveat that
 * made two identical 250/s runs differ by 2.4x.
 *
 * <p>Not run by {@code mvn test}: the class name does not match surefire's
 * include patterns. Use {@code scripts/perf/matching-engine-benchmark.sh}.
 */
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 5)
@Fork(value = 1, jvmArgsAppend = {
        "-XX:+UseZGC",
        "-Xmx4g",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.util=ALL-UNNAMED",
        "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED"})
@State(Scope.Benchmark)
public class MatchingEngineBenchmark {

    private static final int SYMBOL = 1;
    private static final long BUYER = 101L;
    private static final long SELLER = 102L;
    private static final long PRICE_TICKS = 100_000L;
    private static final long QUANTITY_STEPS = 1L;
    /**
     * Orders allowed in flight at once.
     *
     * <p>{@code latencyPerformanceBuilder()} gives a ring of 2048, so this is
     * an eighth of it - deep enough that the engine is never starved, far
     * enough from the edge that ring capacity is not what is being measured.
     */
    private static final int IN_FLIGHT = 256;

    /** true: journal on a parallel stage. false: snapshot after every command. */
    @Param({"true", "false"})
    public boolean journalling;

    private ProductionSimulation simulation;
    private Path storage;
    private long sequence;
    private final ArrayDeque<CompletableFuture<?>> inFlight = new ArrayDeque<>();

    @Setup
    public void start() throws Exception {
        storage = Files.createTempDirectory("matching-engine-benchmark");
        PerformanceConfiguration performance = PerformanceConfiguration.latencyPerformanceBuilder()
                .matchingEnginesNum(1)
                // Matches the configured default. A spinning strategy would
                // measure a venue nobody deploys - see CONFIGURATION.md.
                .waitStrategy(CoreWaitStrategy.BLOCKING)
                .build();
        simulation = ProductionSimulation.start(
                new ProductionSimulationConfiguration("bench", storage, 1, performance, journalling),
                ProductionSimulationAccounting.matchingOnly());
        simulation.addSymbols(Set.of(CoreSymbolSpecification.builder()
                                                            .symbolId(SYMBOL)
                                                            .type(SymbolType.EQUITY)
                                                            .baseCurrency(1)
                                                            .quoteCurrency(2)
                                                            .baseScaleK(1)
                                                            .quoteScaleK(1)
                                                            .takerFee(0)
                                                            .makerFee(0)
                                                            .build()));
    }

    @TearDown
    public void stop() throws Exception {
        if (simulation != null) simulation.close();
        if (storage != null && Files.exists(storage)) {
            try (var paths = Files.walk(storage)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // A benchmark temp directory; nothing depends on it.
                    }
                });
            }
        }
    }

    /**
     * Pipelined throughput: a bounded window of orders in flight.
     *
     * <p>{@link #matchAlternatingSides} joins every order, so its throughput is
     * the reciprocal of its own round-trip latency - 46 us per order is 21,700
     * orders/sec whatever the engine could sustain. That is a <b>latency</b>
     * measurement wearing a throughput unit. This one keeps {@value #IN_FLIGHT}
     * orders outstanding so the engine is continuously fed, which is the number
     * to hold against a published exchange figure.
     *
     * <h2>Why bounded, and what the first attempt actually found</h2>
     * <p>The first version submitted 1000 orders and joined none until the end.
     * It hung: the worker parked in {@code join()} on a future that never
     * completed while the matching thread sat <i>idle</i>. Ring capacity does
     * not explain that - 1000 is under the 2048 ring, and a full ring would
     * block in {@code submit()} rather than {@code join()}. A lost completion
     * with an idle consumer is the signature of a missed wake-up, which is a
     * known hazard of the {@code BLOCKING} wait strategy this benchmark uses
     * deliberately because production uses it.
     *
     * <p>It happened once and did not reproduce on the previous run, so it is
     * recorded rather than diagnosed. The bounded window keeps depth far from
     * anything resembling a capacity edge; if the hang returns at this depth
     * the engine has a liveness problem worth escalating, and this benchmark is
     * where it will show up.
     */
    @Benchmark
    public Object pipelinedSubmit() {
        Object completed = inFlight.size() >= IN_FLIGHT ? inFlight.poll().join() : null;
        long id = ++sequence;
        boolean buy = (id & 1L) == 1L;
        inFlight.add(simulation.submit(new DmaLimitOrder(
                id, id, buy ? BUYER : SELLER, SYMBOL,
                buy ? OrderAction.BID : OrderAction.ASK, PRICE_TICKS, QUANTITY_STEPS)));
        return completed;
    }

    /** Empties the window between iterations so none of it is carried across. */
    @TearDown(Level.Iteration)
    public void drain() {
        while (!inFlight.isEmpty()) {
            inFlight.poll().join();
        }
    }

    /**
     * Alternating sides at one price, so every second order crosses the one
     * before it and the book returns to empty.
     *
     * <p>Deliberate: submitting only resting orders would grow the book for the
     * length of the run and measure book depth as much as matching, and
     * submitting only crossing orders would empty it and measure rejections.
     * This holds the book at roughly zero and exercises the path a trade
     * actually takes.
     */
    @Benchmark
    public Object matchAlternatingSides() {
        long id = ++sequence;
        boolean buy = (id & 1L) == 1L;
        return simulation.submit(new DmaLimitOrder(
                id,
                id,
                buy ? BUYER : SELLER,
                SYMBOL,
                buy ? OrderAction.BID : OrderAction.ASK,
                PRICE_TICKS,
                QUANTITY_STEPS)).join();
    }
}

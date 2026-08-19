package com.emporia.ordermanagement.disruptor;

import com.emporia.execution.OrderIntakeReadiness;
import com.emporia.ha.LeaderElectionService;
import com.emporia.events.TradingEvents.CommandType;
import com.emporia.events.TradingEvents.ExecutionCommand;
import com.emporia.events.TradingEvents.ExecutionCommandType;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderCommandResult;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.ordermanagement.dto.ProcessingOutcome;
import com.emporia.ordermanagement.service.ExecutionCommandHandler;
import com.emporia.ordermanagement.service.OrderCommandHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.emporia.ordermanagement.service.MemoryMappedWalLogger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DisruptorOrderPipelineTest {
    private OrderCommandHandler handler;
    private DisruptorOrderPipeline pipeline;

    @BeforeEach
    void setUp() {
        handler = mock(OrderCommandHandler.class);
        pipeline = new DisruptorOrderPipeline(handler, null, new SimpleMeterRegistry(), disabledWal(), null, null, "yielding", 0, 0, 0, 0, "", "");
        pipeline.start();
    }

    @AfterEach
    void tearDown() {
        if (pipeline != null) {
            pipeline.stop();
        }
    }

    @Test
    void processesSingleCommandThroughRingBuffer() throws Exception {
        UUID commandId = UUID.randomUUID();
        OrderCommandResult resultRecord = new OrderCommandResult(SCHEMA_VERSION, commandId, true, 201, "Created", "{}");
        ProcessingOutcome outcome = new ProcessingOutcome(resultRecord, List.of());
        when(handler.handle(any())).thenReturn(outcome);

        OrderCommand command = sampleCommand(commandId);
        CompletableFuture<ProcessingOutcome> future = pipeline.submit(command);

        ProcessingOutcome result = future.get(5, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.result().commandId()).isEqualTo(commandId);
    }

    @Test
    void processesConcurrentCommandsSequentiallyOnSingleWriterThread() throws Exception {
        int threadCount = 10;
        int commandsPerThread = 100;
        int totalCommands = threadCount * commandsPerThread;

        AtomicInteger handledCount = new AtomicInteger();
        when(handler.handle(any())).thenAnswer(invocation -> {
            handledCount.incrementAndGet();
            OrderCommand cmd = invocation.getArgument(0);
            OrderCommandResult res = new OrderCommandResult(SCHEMA_VERSION, cmd.commandId(), true, 201, "OK", "{}");
            return new ProcessingOutcome(res, List.of());
        });

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalCommands);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < commandsPerThread; j++) {
                        pipeline.submit(sampleCommand(UUID.randomUUID()))
                                .thenRun(doneLatch::countDown);
                    }
                } catch (Exception ignored) {
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(handledCount.get()).isEqualTo(totalCommands);
    }

    @Test
    void rejectsCommandsWhenKillSwitchIsEngaged() {
        pipeline.engageKillSwitch("operator-drill");

        assertThatThrownBy(() -> pipeline.submit(sampleCommand(UUID.randomUUID())).join())
                .hasCauseInstanceOf(HotPathRejectedException.class)
                .cause()
                .hasMessageContaining("kill switch");
    }

    /**
     * Command deduplication is only correct while exactly one instance accepts
     * orders. Nothing on this path used to check that, so a second instance
     * would silently accept duplicates rather than fail.
     */
    @Test
    void rejectsCommandsWhenNotPrimary() {
        LeaderElectionService standby = mock(LeaderElectionService.class);
        when(standby.isPrimary()).thenReturn(false);
        DisruptorOrderPipeline secondary = new DisruptorOrderPipeline(
                handler, null, new SimpleMeterRegistry(), disabledWal(), null, standby,
                "yielding", 0, 0, 0, 0, "", "");
        secondary.start();
        try {
            assertThatThrownBy(() -> secondary.submit(sampleCommand(UUID.randomUUID())).join())
                    .hasCauseInstanceOf(HotPathRejectedException.class)
                    .cause()
                    .hasMessageContaining("not the primary");
        } finally {
            secondary.stop();
        }
    }

    @Test
    void rejectsExecutionCommandsWhenNotPrimary() {
        LeaderElectionService standby = mock(LeaderElectionService.class);
        when(standby.isPrimary()).thenReturn(false);
        ExecutionCommandHandler executionHandler = mock(ExecutionCommandHandler.class);
        DisruptorOrderPipeline secondary = new DisruptorOrderPipeline(
                handler, executionHandler, new SimpleMeterRegistry(), disabledWal(), null, standby,
                "yielding", 0, 0, 0, 0, "", "");
        secondary.start();
        try {
            assertThatThrownBy(() -> secondary.submitExecutionCommand(sampleExecutionCommand()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not the primary");
        } finally {
            secondary.stop();
        }
    }

    @Test
    void acceptsCommandsWhenPrimary() {
        LeaderElectionService primary = mock(LeaderElectionService.class);
        when(primary.isPrimary()).thenReturn(true);
        DisruptorOrderPipeline leader = new DisruptorOrderPipeline(
                handler, null, new SimpleMeterRegistry(), disabledWal(), null, primary,
                "yielding", 0, 0, 0, 0, "", "");
        leader.start();
        try {
            // The mocked handler returns null, so assert the command reached it
            // rather than asserting on an outcome this test never provides.
            leader.submit(sampleCommand(UUID.randomUUID())).join();
            verify(handler).handle(any());
        } finally {
            leader.stop();
        }
    }

    @Test
    void rejectsOrderCommandsWhenExecutionVenueIsNotReady() {
        DisruptorOrderPipeline waitingForVenue = new DisruptorOrderPipeline(
                handler, null, new SimpleMeterRegistry(), disabledWal(), null, null,
                "yielding", 0, 0, 0, 0, "", "");
        waitingForVenue.setExecutionVenueReadinessForTest(() -> OrderIntakeReadiness.notReady(
                "execution_venue_not_ready", "Execution venue is still recovering; retry shortly"));
        waitingForVenue.start();
        try {
            assertThatThrownBy(() -> waitingForVenue.submit(sampleCommand(UUID.randomUUID())).join())
                    .hasCauseInstanceOf(HotPathRejectedException.class)
                    .cause()
                    .isInstanceOfSatisfying(HotPathRejectedException.class, rejection -> {
                        assertThat(rejection.status()).isEqualTo(503);
                        assertThat(rejection.reason()).isEqualTo("execution_venue_not_ready");
                    })
                    .hasMessageContaining("still recovering");
            verify(handler, org.mockito.Mockito.never()).handle(any());
        } finally {
            waitingForVenue.stop();
        }
    }

    @Test
    void rejectsIngressBurstWhenConsumerFallsBehind() throws Exception {
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DisruptorOrderPipeline constrained = new DisruptorOrderPipeline(
                handler,
                null,
                new SimpleMeterRegistry(),
                disabledWal(),
                null,
                null,
                "yielding",
                65_535,
                0,
                0,
                0,
                "",
                ""
        );
        when(handler.handle(any())).thenAnswer(invocation -> {
            handlerEntered.countDown();
            release.await(5, TimeUnit.SECONDS);
            OrderCommand command = invocation.getArgument(0);
            return new ProcessingOutcome(new OrderCommandResult(SCHEMA_VERSION, command.commandId(), true, 201, null, "{}"), List.of());
        });
        constrained.start();
        try {
            CompletableFuture<ProcessingOutcome> first = constrained.submit(sampleCommand(UUID.randomUUID()));
            assertThat(handlerEntered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> constrained.submit(sampleCommand(UUID.randomUUID())).join())
                    .hasCauseInstanceOf(HotPathRejectedException.class)
                    .cause()
                    .hasMessageContaining("overloaded");

            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isNotNull();
        } finally {
            release.countDown();
            constrained.stop();
        }
    }

    private static OrderCommand sampleCommand(UUID commandId) {
        return new OrderCommand(SCHEMA_VERSION, commandId, CommandType.CREATE,
                "trader-1", "DESK-A", Instant.now(), UUID.randomUUID(), null, null,
                OrderSide.BUY, OrderType.LIMIT, new BigDecimal("100"), new BigDecimal("150.00"),
                "DMA", "ref-1", null, java.util.Map.of());
    }

    private static ExecutionCommand sampleExecutionCommand() {
        return new ExecutionCommand(SCHEMA_VERSION, UUID.randomUUID(), ExecutionCommandType.FILL,
                UUID.randomUUID(), "DESK-A", "exec-ref-1", new BigDecimal("100"),
                new BigDecimal("150.00"), "exchange-core", Instant.now(), null);
    }

    /**
     * LMAX_ARCHITECTURE_REWORK_PLAN.md task 4.3: execution commands dispatch
     * to ExecutionCommandHandler through the same ring order commands use,
     * not through ShardedOrderDispatcher's shard threads.
     */
    @Test
    void routesExecutionCommandsToTheExecutionHandlerThroughTheSameRing() throws Exception {
        ExecutionCommandHandler executionHandler = mock(ExecutionCommandHandler.class);
        DisruptorOrderPipeline withExecution = new DisruptorOrderPipeline(
                handler, executionHandler, new SimpleMeterRegistry(), disabledWal(), null, null,
                "yielding", 0, 0, 0, 0, "", "");
        withExecution.start();
        try {
            ExecutionCommand command = sampleExecutionCommand();
            CompletableFuture<Void> future = withExecution.submitExecutionCommand(command);

            future.get(5, TimeUnit.SECONDS);
            verify(executionHandler).handle(command);
        } finally {
            withExecution.stop();
        }
    }

    /**
     * The bug this guards against: a fill already happened at the venue, the
     * handler that records it in OMS throws, and the returned future was not
     * wired up - the failure would be recorded on the ring slot and lost the
     * moment the slot is reset for its next use, with no caller ever able to
     * observe it. Silent state divergence from exchange-core, for real money.
     */
    @Test
    void submitExecutionCommandFailsTheFutureWhenTheHandlerThrows() throws Exception {
        ExecutionCommandHandler executionHandler = mock(ExecutionCommandHandler.class);
        RuntimeException handlerFailure = new RuntimeException("could not record fill");
        when(executionHandler.handle(any())).thenThrow(handlerFailure);
        DisruptorOrderPipeline withExecution = new DisruptorOrderPipeline(
                handler, executionHandler, new SimpleMeterRegistry(), disabledWal(), null, null,
                "yielding", 0, 0, 0, 0, "", "");
        withExecution.start();
        try {
            CompletableFuture<Void> future = withExecution.submitExecutionCommand(sampleExecutionCommand());

            assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                    .hasCause(handlerFailure);
        } finally {
            withExecution.stop();
        }
    }

    /**
     * The guard in submitExecutionCommand's javadoc: blocking there would
     * deadlock the writer thread against ring capacity only its own progress
     * reclaims. Simulated by having the order-command handler itself - which
     * runs on the writer thread - call submitExecutionCommand.
     */
    @Test
    void submitExecutionCommandRefusesToRunOnTheWriterThread() throws Exception {
        ExecutionCommandHandler executionHandler = mock(ExecutionCommandHandler.class);
        DisruptorOrderPipeline withExecution = new DisruptorOrderPipeline(
                handler, executionHandler, new SimpleMeterRegistry(), disabledWal(), null, null,
                "yielding", 0, 0, 0, 0, "", "");
        AtomicReference<Throwable> caughtOnWriterThread = new AtomicReference<>();
        when(handler.handle(any())).thenAnswer(invocation -> {
            try {
                withExecution.submitExecutionCommand(sampleExecutionCommand());
            } catch (Throwable thrown) {
                caughtOnWriterThread.set(thrown);
            }
            OrderCommand cmd = invocation.getArgument(0);
            return new ProcessingOutcome(
                    new OrderCommandResult(SCHEMA_VERSION, cmd.commandId(), true, 201, "OK", "{}"),
                    List.of());
        });
        withExecution.start();
        try {
            withExecution.submit(sampleCommand(UUID.randomUUID())).join();

            assertThat(caughtOnWriterThread.get())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("writer thread");
        } finally {
            withExecution.stop();
        }
    }

    @Test
    void recordsTheCommandBeforeApplyingIt() throws Exception {
        java.nio.file.Path walFile = java.nio.file.Files.createTempDirectory("wal-ahead")
                .resolve("ahead.log");
        try (MemoryMappedWalLogger wal = new MemoryMappedWalLogger(walFile.toString(), 1)) {
            OrderCommandHandler recordingHandler = mock(OrderCommandHandler.class);
            AtomicInteger positionWhenHandlerRan = new AtomicInteger(-1);
            when(recordingHandler.handle(any())).thenAnswer(invocation -> {
                positionWhenHandlerRan.set(wal.position());
                return null;
            });
            DisruptorOrderPipeline logging = new DisruptorOrderPipeline(
                    recordingHandler, null, new SimpleMeterRegistry(), wal, null, null,
                    "yielding", 0, 0, 0, 0, "", "");
            logging.start();

            logging.submit(sampleCommand(UUID.randomUUID())).join();

            // Write-ahead in the literal sense. A record written after the
            // handler cannot describe a command the process died during, so the
            // log must already hold it by the time the handler runs.
            assertThat(positionWhenHandlerRan.get()).isPositive();
            logging.stop();
        }
    }

    @Test
    void failsTheCommandRatherThanApplyingItUnrecorded() throws Exception {
        java.nio.file.Path walFile = java.nio.file.Files.createTempDirectory("wal-full")
                .resolve("full.log");
        // One megabyte, filled by the first append, so the second cannot be recorded.
        try (MemoryMappedWalLogger wal = new MemoryMappedWalLogger(walFile.toString(), 1)) {
            wal.append(new byte[1024 * 1024 - 8]);
            OrderCommandHandler neverCalled = mock(OrderCommandHandler.class);
            DisruptorOrderPipeline logging = new DisruptorOrderPipeline(
                    neverCalled, null, new SimpleMeterRegistry(), wal, null, null,
                    "yielding", 0, 0, 0, 0, "", "");
            logging.start();

            // Accepting an order the system has no durable trace of, while
            // telling the caller it succeeded, is the loss this log prevents.
            assertThatThrownBy(() -> logging.submit(sampleCommand(UUID.randomUUID())).join())
                    .hasRootCauseInstanceOf(HotPathRejectedException.class);
            org.mockito.Mockito.verify(neverCalled, org.mockito.Mockito.never()).handle(any());
            logging.stop();
        }
    }

    private static MemoryMappedWalLogger disabledWal() {
        return new MemoryMappedWalLogger(null, 1);
    }
}

package com.emporia.ordermanagement.disruptor;

import com.emporia.events.TradingEvents.CommandType;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderCommandResult;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.ordermanagement.dto.ProcessingOutcome;
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

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DisruptorOrderPipelineTest {
    private OrderCommandHandler handler;
    private DisruptorOrderPipeline pipeline;

    @BeforeEach
    void setUp() {
        handler = mock(OrderCommandHandler.class);
        pipeline = new DisruptorOrderPipeline(handler, new SimpleMeterRegistry(), disabledWal(), null, "yielding", 0, 0, 0, "", "");
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

    @Test
    void rejectsIngressBurstWhenConsumerFallsBehind() throws Exception {
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DisruptorOrderPipeline constrained = new DisruptorOrderPipeline(
                handler,
                new SimpleMeterRegistry(),
                disabledWal(),
                null,
                "yielding",
                65_535,
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
                    recordingHandler, new SimpleMeterRegistry(), wal, null,
                    "yielding", 0, 0, 0, "", "");
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
                    neverCalled, new SimpleMeterRegistry(), wal, null,
                    "yielding", 0, 0, 0, "", "");
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

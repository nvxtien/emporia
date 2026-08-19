package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.CommandType;
import com.emporia.events.TradingEvents.ExecutionCommand;
import com.emporia.events.TradingEvents.ExecutionCommandType;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.ordermanagement.disruptor.DisruptorOrderPipeline;
import com.emporia.ordermanagement.dto.ProcessingOutcome;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.ExecutionRepository;
import com.emporia.ordermanagement.repository.ProcessedCommandRepository;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * LMAX_ARCHITECTURE_REWORK_PLAN.md task 5.4: order commands and execution
 * commands now share one ring (task 5.1), so the outcome of a race between a
 * user action and a venue action must depend only on which one the ring
 * sequenced first - never on which producer thread happened to win in real
 * wall-clock time. These tests use real {@link OrderCommandHandler} and
 * {@link ExecutionCommandHandler} instances sharing one {@link OrderStateCache}
 * and one {@link DisruptorOrderPipeline}, not mocks - the property under test
 * is genuine cross-thread determinism, which a single-threaded unit test
 * calling handler methods directly cannot exercise.
 */
class DisruptorOrderPipelineInterleavingTest {

    private static final String USER = "trader-1";

    private final TradingOrderRepository orders = mock(TradingOrderRepository.class);
    private final ExecutionRepository executions = mock(ExecutionRepository.class);
    private final ProcessedCommandRepository processed = mock(ProcessedCommandRepository.class);
    private final OrderMetrics metrics = new OrderMetrics(new SimpleMeterRegistry());
    private final OrderStateCache cache = new OrderStateCache(orders, processed, metrics, null, 1000, 1000);
    private final AsyncDbWriter asyncDbWriter = mock(AsyncDbWriter.class);
    private DisruptorOrderPipeline pipeline;

    @BeforeEach
    void setUp() {
        // Complete from the start (this test class lives in the same package
        // as OrderStateCache, so markLiveSetComplete() is reachable): the new
        // CANCEL_ALL/parent-child tests need liveOrdersOnDesk/liveChildrenOf to
        // answer from the real index, not silently no-op through the mocked
        // repository's empty-list default. Safe for the existing tests too -
        // none of them ever index a child, so the real index answers exactly
        // as empty as the mocked fallback did.
        cache.markLiveSetComplete();
        OrderCommandHandler orderHandler = new OrderCommandHandler(
                orders, new ObjectMapper(), ObservationRegistry.NOOP, metrics, cache, asyncDbWriter);
        ExecutionCommandHandler executionHandler = new ExecutionCommandHandler(
                orders, executions, new ObjectMapper(), metrics, cache, asyncDbWriter, null);
        pipeline = new DisruptorOrderPipeline(
                orderHandler, executionHandler, new SimpleMeterRegistry(), disabledWal(), null, null,
                "yielding", 0, 0, 0, 0, "", "");
        pipeline.start();
    }

    @AfterEach
    void tearDown() {
        if (pipeline != null) pipeline.stop();
    }

    /**
     * Both orderings of the same two commands, run without racing (main
     * thread submits sequentially), each asserted against the specific
     * outcome ring order predicts. Together they show the outcome flips with
     * arrival order rather than being fixed by which command *type* it is -
     * the property "ring order determines outcome" actually claims.
     */
    @Test
    void rejectBeforeModifyLeavesTheModifyDeterministicallyRejected() throws Exception {
        TradingOrder order = seedLiveOrder();

        pipeline.submitExecutionCommand(rejectCommand(order)).join();
        ProcessingOutcome modifyOutcome = pipeline.submit(modifyCommand(order, order.getVersion())).join();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(modifyOutcome.result().success())
                .as("a REJECT the ring already applied must make the following MODIFY fail, "
                        + "not silently reactivate a terminal order")
                .isFalse();
        assertThat(order.getQuantity())
                .as("the rejected modify must not have changed the order")
                .isEqualByComparingTo("10");
    }

    @Test
    void modifyBeforeRejectPreservesTheModifiedQuantity() throws Exception {
        TradingOrder order = seedLiveOrder();

        ProcessingOutcome modifyOutcome = pipeline.submit(modifyCommand(order, order.getVersion())).join();
        pipeline.submitExecutionCommand(rejectCommand(order)).join();

        assertThat(modifyOutcome.result().success()).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getQuantity())
                .as("the modify the ring already applied before the reject arrived must not be lost")
                .isEqualByComparingTo("20");
    }

    /**
     * The genuine race: two producer threads released simultaneously, real
     * contention for the ring. Repeated, because a race that only sometimes
     * exposes a defect is exactly what a single fixed-order test cannot catch.
     * The assertion is deliberately not "which one won" - that is real
     * wall-clock timing and not this test's business - it is that the result
     * is always one of the two well-defined outcomes above, never a third,
     * corrupted one (e.g. success flipped, quantity partially applied).
     */
    @Test
    void racingModifyAndRejectAlwaysLandOnAWellDefinedOutcome() throws Exception {
        for (int iteration = 0; iteration < 50; iteration++) {
            TradingOrder order = seedLiveOrder();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            CompletableFuture<ProcessingOutcome> modifyOutcome = new CompletableFuture<>();

            Thread modifyThread = new Thread(() -> {
                ready.countDown();
                await(go);
                modifyOutcome.complete(pipeline.submit(modifyCommand(order, order.getVersion())).join());
            });
            Thread rejectThread = new Thread(() -> {
                ready.countDown();
                await(go);
                pipeline.submitExecutionCommand(rejectCommand(order)).join();
            });

            modifyThread.start();
            rejectThread.start();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            modifyThread.join(5000);
            rejectThread.join(5000);

            assertThat(order.getStatus())
                    .as("iteration %d: a race must still terminate the order exactly once", iteration)
                    .isEqualTo(OrderStatus.REJECTED);
            boolean modifySucceeded = modifyOutcome.get(5, TimeUnit.SECONDS).result().success();
            BigDecimal expectedQuantity = modifySucceeded ? new BigDecimal("20") : new BigDecimal("10");
            assertThat(order.getQuantity())
                    .as("iteration %d: quantity must match whichever outcome actually won, never a mix", iteration)
                    .isEqualByComparingTo(expectedQuantity);
        }
    }

    /**
     * Cancel requested by the user, then a partial fill from the venue -
     * today's business rule (see ExecutionCommandHandlerTest) is that a fill
     * already in flight still applies while a cancel is pending. What this
     * test adds is that it holds when the two are genuinely racing producer
     * threads through the real ring, not just sequential calls in one thread.
     */
    @Test
    void racingCancelAndPartialFillNeverLoseOrDuplicateEitherEffect() throws Exception {
        for (int iteration = 0; iteration < 50; iteration++) {
            TradingOrder order = seedLiveOrder();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);

            Thread cancelThread = new Thread(() -> {
                ready.countDown();
                await(go);
                pipeline.submit(cancelCommand(order)).join();
            });
            Thread fillThread = new Thread(() -> {
                ready.countDown();
                await(go);
                pipeline.submitExecutionCommand(
                        fillCommand(order, new BigDecimal("4"), new BigDecimal("101.00"))).join();
            });

            cancelThread.start();
            fillThread.start();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            cancelThread.join(5000);
            fillThread.join(5000);

            assertThat(order.getTargetStatus())
                    .as("iteration %d: the cancel request must always be recorded", iteration)
                    .isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getTradedQuantity())
                    .as("iteration %d: the fill must be applied exactly once, never lost or doubled", iteration)
                    .isEqualByComparingTo("4");
            assertThat(order.getRemainingQuantity())
                    .as("iteration %d: quantity accounting must stay internally consistent either way", iteration)
                    .isEqualByComparingTo(order.getQuantity().subtract(order.getTradedQuantity()));
        }
    }

    /**
     * CANCEL_ALL reads a snapshot of {@code liveOrdersOnDesk} at the moment
     * the ring processes it - whether a concurrently-created order is in that
     * snapshot depends entirely on which the ring sequenced first, not on
     * real submission timing.
     */
    @Test
    void cancelAllBeforeCreateLeavesTheNewOrderUntouched() throws Exception {
        seedLiveOrder();
        pipeline.submit(cancelAllCommand()).join();

        UUID newOrderId = UUID.randomUUID();
        ProcessingOutcome createOutcome = pipeline.submit(createCommand(newOrderId, null)).join();

        assertThat(createOutcome.result().success()).isTrue();
        TradingOrder created = cache.findByIdAndDeskId(newOrderId, USER).orElseThrow();
        assertThat(created.getTargetStatus())
                .as("CANCEL_ALL ran before this order existed, so it must not have been swept up")
                .isEqualTo(OrderStatus.LIVE);
    }

    @Test
    void createBeforeCancelAllSweepsUpTheNewOrderToo() throws Exception {
        UUID newOrderId = UUID.randomUUID();
        assertThat(pipeline.submit(createCommand(newOrderId, null)).join().result().success()).isTrue();

        pipeline.submit(cancelAllCommand()).join();

        TradingOrder created = cache.findByIdAndDeskId(newOrderId, USER).orElseThrow();
        assertThat(created.getTargetStatus())
                .as("CANCEL_ALL ran after this order existed, so it must have swept it up too")
                .isEqualTo(OrderStatus.CANCELLED);
    }

    /**
     * A child create checks its parent's target status, so a parent cancel
     * that reached the ring first must deterministically block it - and one
     * that reached it after a successful create must deterministically cascade
     * to cancel the child, not leave it orphaned live.
     */
    @Test
    void cancelParentBeforeChildCreateRejectsTheChild() throws Exception {
        TradingOrder parent = seedLiveOrder();
        pipeline.submit(cancelCommand(parent)).join();

        ProcessingOutcome createOutcome =
                pipeline.submit(createCommand(UUID.randomUUID(), parent.getId())).join();

        assertThat(createOutcome.result().success())
                .as("the parent's cancellation was already pending when this child tried to attach")
                .isFalse();
    }

    @Test
    void childCreateBeforeParentCancelCascadesTheCancellationToTheChild() throws Exception {
        TradingOrder parent = seedLiveOrder();
        UUID childId = UUID.randomUUID();
        assertThat(pipeline.submit(createCommand(childId, parent.getId())).join().result().success()).isTrue();

        pipeline.submit(cancelCommand(parent)).join();

        TradingOrder child = cache.findByIdAndDeskId(childId, USER).orElseThrow();
        assertThat(child.getTargetStatus())
                .as("the parent cancel must cascade to a child that already existed when it ran")
                .isEqualTo(OrderStatus.CANCELLED);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private TradingOrder seedLiveOrder() {
        TradingOrder order = new TradingOrder(
                UUID.randomUUID(), USER, USER, listing(), OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"), "DMA", "interleaving-test",
                null, null, "{}");
        ReflectionTestUtils.setField(order, "version", 0L);
        cache.put(order);
        return order;
    }

    private static OrderCommand createCommand(UUID orderId, UUID parentOrderId) {
        return new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.CREATE,
                USER, Instant.EPOCH, orderId, null, listing(),
                OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"), "DMA", "interleaving-child",
                parentOrderId, Map.of());
    }

    private static OrderCommand cancelAllCommand() {
        return new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.CANCEL_ALL,
                USER, Instant.EPOCH, null, null, null,
                null, null, null, null, null, null, null, Map.of());
    }

    private static OrderCommand modifyCommand(TradingOrder order, long expectedVersion) {
        return new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.MODIFY,
                USER, Instant.EPOCH, order.getId(), expectedVersion, null,
                null, null, new BigDecimal("20"), new BigDecimal("105"), null, null, null, Map.of());
    }

    private static OrderCommand cancelCommand(TradingOrder order) {
        return new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.CANCEL,
                USER, Instant.EPOCH, order.getId(), null, null,
                null, null, null, null, null, null, null, Map.of());
    }

    private static ExecutionCommand rejectCommand(TradingOrder order) {
        return new ExecutionCommand(
                SCHEMA_VERSION, UUID.randomUUID(), ExecutionCommandType.REJECT, order.getId(), USER,
                "reject-" + order.getId(), null, null, "XNAS", Instant.now(), "Rejected by venue");
    }

    private static ExecutionCommand fillCommand(TradingOrder order, BigDecimal quantity, BigDecimal price) {
        return new ExecutionCommand(
                SCHEMA_VERSION, UUID.randomUUID(), ExecutionCommandType.FILL, order.getId(), USER,
                "fill-" + UUID.randomUUID(), quantity, price, "XNAS", Instant.now(), null);
    }

    private static ListingSnapshot listing() {
        return new ListingSnapshot(
                1, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq", "US", "USD",
                new BigDecimal("0.01"), new BigDecimal("0.01"),
                new BigDecimal("200"), new BigDecimal("198"));
    }

    private static MemoryMappedWalLogger disabledWal() {
        return new MemoryMappedWalLogger(null, 1);
    }
}

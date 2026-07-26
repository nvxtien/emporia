package com.emporia.ordermanagement.fray;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.ordermanagement.model.TradingOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pastalab.fray.junit.junit5.FrayTestExtension;
import org.pastalab.fray.junit.junit5.annotations.FrayTest;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Explores the in-memory cancel-versus-fill race under Fray's controlled scheduler.
 *
 * <p>This opt-in specification is selected by the {@code fray} Maven profile.
 * Fray instruments JVM synchronization points and executes the same test under many
 * thread schedules, making uncommon interleavings reproducible.
 *
 * <p>Unlike {@code TradingOrderPostgresConcurrencySpec}, this test deliberately does
 * not involve JPA, optimistic locking, or a database. It verifies that the synchronized
 * domain methods on one shared {@link TradingOrder} serialize competing transitions
 * and preserve the order's numeric and state-machine invariants.
 */
@ExtendWith(FrayTestExtension.class)
class TradingOrderFraySpec {

    // Fray reruns this scenario 200 times while choosing controlled thread schedules.
    @FrayTest(iterations = 200)
    void fullFillRacingCancellationHasOneValidTerminalWinner() throws InterruptedException {
        // Both threads intentionally mutate the same in-memory entity instance.
        TradingOrder order = order();

        // "ready" prevents an early thread from finishing before its competitor is
        // started. "start" releases both competitors into the race together.
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        // Worker failures cannot be thrown directly on the JUnit thread, so each
        // worker records its result for deterministic assertions after join().
        AtomicReference<Throwable> fillFailure = new AtomicReference<>();
        AtomicReference<Throwable> cancelFailure = new AtomicReference<>();

        // A complete fill moves LIVE -> FILLED and consumes all remaining quantity.
        Thread fill = new Thread(
                () -> runAfterStart(
                        ready,
                        start,
                        () -> order.applyFill(new BigDecimal("100.00"), new BigDecimal("25.10")),
                        fillFailure
                ),
                "apply-full-fill"
        );

        // Cancellation competes for the same TradingOrder monitor and tries to move
        // LIVE -> CANCELLED before the fill acquires it.
        Thread cancel = new Thread(
                () -> runAfterStart(ready, start, order::cancel, cancelFailure),
                "cancel-order"
        );

        fill.start();
        cancel.start();
        ready.await();
        start.countDown();
        fill.join();
        cancel.join();

        // The synchronized transition that acquires the monitor first succeeds. The
        // second sees a terminal status and must be rejected, so XOR must be true.
        assertThat((fillFailure.get() == null) ^ (cancelFailure.get() == null)).isTrue();

        // Every explored schedule must leave the shared entity internally consistent.
        assertThatCode(order::validateInvariants).doesNotThrowAnyException();

        // If fill won, cancellation observed FILLED and no fill accounting was lost.
        if (order.getStatus() == OrderStatus.FILLED) {
            assertThat(fillFailure.get()).isNull();
            assertThat(cancelFailure.get())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Only active orders can be cancelled");
            assertThat(order.getTradedQuantity()).isEqualByComparingTo("100.00");
            assertThat(order.getRemainingQuantity()).isEqualByComparingTo("0.00");
            assertThat(order.getAverageTradePrice()).isEqualByComparingTo("25.100000");
        } else {
            // If cancellation won, fill observed CANCELLED and the original unfilled
            // quantities remained unchanged.
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(cancelFailure.get()).isNull();
            assertThat(fillFailure.get())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Only active orders can receive fills");
            assertThat(order.getTradedQuantity()).isEqualByComparingTo("0.00");
            assertThat(order.getRemainingQuantity()).isEqualByComparingTo("100.00");
            assertThat(order.getAverageTradePrice()).isNull();
        }
    }

    private static void runAfterStart(
            CountDownLatch ready,
            CountDownLatch start,
            Runnable transition,
            AtomicReference<Throwable> failure
    ) {
        // Signal readiness before blocking so the JUnit thread knows that both
        // workers have reached the scheduler-visible starting barrier.
        ready.countDown();
        try {
            start.await();
            transition.run();
        } catch (Throwable throwable) {
            // Preserve the losing transition's domain exception for assertions on
            // the JUnit thread instead of allowing an uncaught worker exception.
            failure.set(throwable);
        }
    }

    private static TradingOrder order() {
        UUID orderId = UUID.fromString("4c5b664f-f4cb-40cf-b729-1b16f868f04b");
        return new TradingOrder(
                orderId,
                "fray-test-user",
                listing(),
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("100.00"),
                new BigDecimal("25.00"),
                "DMA",
                "fray-cancel-fill-race",
                null,
                orderId,
                "{}"
        );
    }

    private static ListingSnapshot listing() {
        return new ListingSnapshot(
                1,
                1,
                "AAPL",
                "Apple Inc.",
                "AAPL",
                "XNAS",
                "Nasdaq",
                "US",
                "USD",
                new BigDecimal("0.01"),
                new BigDecimal("0.01"),
                new BigDecimal("200.00"),
                new BigDecimal("198.00")
        );
    }
}

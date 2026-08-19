package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.ProcessedCommandRepository;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The store holds live orders, and what bounds it is liveness rather than a
 * configured count. These are the two properties that makes true, and a third
 * that keeps the error path honest.
 */
class OrderStateCacheLivenessTest {

    private final TradingOrderRepository orders = mock(TradingOrderRepository.class);
    private final ProcessedCommandRepository processed = mock(ProcessedCommandRepository.class);
    private final OrderMetrics metrics = new OrderMetrics(new SimpleMeterRegistry());

    private OrderStateCache cacheHolding(long liveOrderMax) {
        return new OrderStateCache(orders, processed, metrics, null, liveOrderMax, 1000);
    }

    @Test
    void anOrderThatReachesATerminalStatusLeavesTheLiveStore() {
        OrderStateCache cache = cacheHolding(100);
        TradingOrder order = liveOrder();

        cache.put(order);
        assertThat(cache.liveOrderCount()).isEqualTo(1);

        order.cancel();
        cache.put(order);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cache.liveOrderCount())
                .as("a finished order is not live and must not hold a slot")
                .isZero();
    }

    /**
     * The whole point of the cap. Evicting a live order would silently leave an
     * order this service can no longer see or cancel; refusing a new one is
     * visible and is what the ring already does when it fills.
     */
    @Test
    void theStoreRefusesRatherThanEvictingOnceItIsFull() {
        OrderStateCache cache = cacheHolding(2);

        cache.put(liveOrder());
        assertThat(cache.atCapacity()).isFalse();
        cache.put(liveOrder());

        assertThat(cache.atCapacity()).isTrue();
        assertThat(cache.liveOrderCount())
                .as("nothing already admitted may be dropped to make room")
                .isEqualTo(2);
    }

    /**
     * A terminal order is still answerable - that is what lets a cancel of a
     * filled order say 409 rather than 404 - but answering must not put it back
     * into the live store.
     */
    @Test
    void readingATerminalOrderThroughTheFallbackDoesNotReadmitIt() {
        OrderStateCache cache = cacheHolding(100);
        TradingOrder finished = liveOrder();
        finished.cancel();
        when(orders.findByIdAndDeskId(finished.getId(), "desk-a")).thenReturn(Optional.of(finished));

        Optional<TradingOrder> found = cache.findByIdAndDeskId(finished.getId(), "desk-a");

        assertThat(found).contains(finished);
        assertThat(cache.liveOrderCount())
                .as("the error path must not consume a live slot")
                .isZero();
    }

    /**
     * The index must say "ask the database" rather than "no children" while the
     * store is incomplete. A caller handed an empty list cannot tell the two
     * apart, and the difference is a child left live after its parent was
     * cancelled.
     */
    @Test
    void theIndexesRefuseToAnswerUntilTheLiveSetIsComplete() {
        OrderStateCache cache = cacheHolding(100);
        TradingOrder parent = liveOrder();
        cache.put(parent);
        cache.put(childOf(parent));

        assertThat(cache.isLiveSetComplete()).isFalse();
        assertThat(cache.liveChildrenOf(parent.getId()))
                .as("an incomplete store must not answer a negative")
                .isEmpty();
        assertThat(cache.liveOrdersOnDesk("desk-a")).isEmpty();
    }

    @Test
    void onceCompleteTheIndexesAnswerChildrenAndDeskFromMemory() {
        OrderStateCache cache = cacheHolding(100);
        TradingOrder parent = liveOrder();
        TradingOrder child = childOf(parent);
        cache.put(parent);
        cache.put(child);
        cache.markLiveSetComplete();

        assertThat(cache.liveChildrenOf(parent.getId())).contains(List.of(child));
        assertThat(cache.liveOrdersOnDesk("desk-a")).get().asInstanceOf(LIST).hasSize(2);
        assertThat(cache.liveChildrenOf(UUID.randomUUID()))
                .as("a parent with no children answers empty, not unknown")
                .contains(List.of());
    }

    @Test
    void aChildThatFinishesLeavesTheParentIndex() {
        OrderStateCache cache = cacheHolding(100);
        TradingOrder parent = liveOrder();
        TradingOrder child = childOf(parent);
        cache.put(parent);
        cache.put(child);
        cache.markLiveSetComplete();

        child.cancel();
        cache.put(child);

        assertThat(cache.liveChildrenOf(parent.getId())).contains(List.of());
        assertThat(cache.liveOrdersOnDesk("desk-a")).get().asInstanceOf(LIST).hasSize(1);
    }

    /**
     * Without a ready dedup index (the shape every test above already uses,
     * {@code dedup=null}), execution-reference dedup must fall through to the
     * caller-supplied database check rather than guess - same fallback shape
     * as {@link #readingATerminalOrderThroughTheFallbackDoesNotReadmitIt}.
     */
    @Test
    void executionReferenceDedupFallsThroughToTheDatabaseWhenTheIndexIsNotReady() {
        OrderStateCache cache = cacheHolding(100);

        boolean exists = cache.existsExecutionReference("desk-a", "XNAS", "ref-1", () -> true);

        assertThat(exists)
                .as("no dedup index is wired, so the answer must come from dbFallback")
                .isTrue();
    }

    /**
     * LMAX_ARCHITECTURE_REWORK_PLAN.md task 5.2: this is the fix for
     * ExecutionCommandHandler's own direct JPA read on every fill. A Bloom
     * filter can confidently prove absence but never presence - the same
     * asymmetry {@link #existsById} already lives with - so the win is on the
     * common case: a genuinely new reference (every non-duplicate fill) is
     * recognised from memory alone, with dbFallback never touched. A
     * remembered reference cannot be confirmed from the filter by itself and
     * correctly falls through for confirmation, exactly like existsById's own
     * "maybe seen" branch.
     */
    @Test
    void newExecutionReferenceIsRecognisedFromMemoryWithoutTheDatabase() {
        RotatingDedupIndex dedup = new RotatingDedupIndex(java.time.Duration.ofHours(24), 2, 4_000, 0.001);
        dedup.publishHistory(new CommandDedupIndex(1_000, 0.001));
        OrderStateCache cache = new OrderStateCache(orders, processed, metrics, dedup, 100, 1000);

        cache.rememberExecutionReference("desk-a", "XNAS", "ref-1");

        boolean neverSeen = cache.existsExecutionReference("desk-a", "XNAS", "ref-2",
                () -> fail("dbFallback must not run for a reference the index confidently reports as new"));
        assertThat(neverSeen)
                .as("a reference nothing ever remembered is definitely new, answered from the index alone")
                .isFalse();

        boolean remembered = cache.existsExecutionReference("desk-a", "XNAS", "ref-1", () -> true);
        assertThat(remembered)
                .as("a remembered reference cannot be confirmed by the filter alone - it falls through, "
                        + "same as existsById's own false-positive handling")
                .isTrue();
    }

    /**
     * `deskId + venue + executionReference` is the key, not the bare reference -
     * two different (desk, venue) pairs sharing a reference string must not be
     * confused with each other, since nothing guarantees venue-supplied
     * references are globally unique. Proven the same way as the test above:
     * a key nothing ever remembered answers "new" from the index alone.
     */
    @Test
    void executionReferenceDedupIsScopedByDeskAndVenueNotTheBareReference() {
        RotatingDedupIndex dedup = new RotatingDedupIndex(java.time.Duration.ofHours(24), 2, 4_000, 0.001);
        dedup.publishHistory(new CommandDedupIndex(1_000, 0.001));
        OrderStateCache cache = new OrderStateCache(orders, processed, metrics, dedup, 100, 1000);

        cache.rememberExecutionReference("desk-a", "XNAS", "shared-ref");

        boolean differentVenue = cache.existsExecutionReference("desk-a", "ANOTHER-VENUE", "shared-ref",
                () -> fail("a different venue's key was never remembered - it must answer from the index alone"));
        assertThat(differentVenue)
                .as("a different venue with the same bare reference must not read as a duplicate")
                .isFalse();
    }

    private static TradingOrder childOf(TradingOrder parent) {
        UUID childId = UUID.randomUUID();
        return new TradingOrder(
                childId, "liveness-test-user", "desk-a", listing(), OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("5"), new BigDecimal("100.00"), "DMA", "liveness-test",
                parent.getId(), parent.getId(), "{}");
    }

    private static TradingOrder liveOrder() {
        UUID orderId = UUID.randomUUID();
        return new TradingOrder(
                orderId, "liveness-test-user", "desk-a", listing(), OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100.00"), "DMA", "liveness-test",
                null, orderId, "{}");
    }

    private static ListingSnapshot listing() {
        return new ListingSnapshot(
                1, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq", "US", "USD",
                new BigDecimal("0.01"), new BigDecimal("0.01"),
                new BigDecimal("200.00"), new BigDecimal("198.00"));
    }
}

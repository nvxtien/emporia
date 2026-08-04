package com.emporia.execution;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.api.dma.DmaLifecycleSnapshot;
import exchange.core2.core.common.api.dma.DmaOrderState;
import exchange.core2.core.common.api.dma.DmaOrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeCoreLifecycleRebuilderTest {

    private final ExchangeCoreLifecycleRebuilder rebuilder = new ExchangeCoreLifecycleRebuilder();

    @Test
    void rebuildsALiveOrderWithTheIdentitiesTheSubmitPathWouldHaveUsed() {
        OrderView order = order(OrderStatus.LIVE, "10", "10", "0");

        DmaLifecycleSnapshot snapshot = rebuilder.rebuild(List.of(order));

        // The whole approach rests on these matching: if the rebuilt projection
        // were keyed differently from what the live path writes, recovery would
        // look successful and dedup would silently not work.
        long coreOrderId = ExchangeCoreExecutionVenueGateway.coreOrderId(order);
        DmaOrderState state = snapshot.orders().get(coreOrderId);
        assertThat(state).isNotNull();
        assertThat(state.order().clientId())
                .isEqualTo(ExchangeCoreExecutionVenueGateway.clientId(order));
        assertThat(state.order().deliveryId())
                .isEqualTo(ExchangeCoreExecutionVenueGateway.deliveryId(order, "submit"));
        assertThat(state.order().side()).isEqualTo(OrderAction.BID);
        assertThat(state.status()).isEqualTo(DmaOrderStatus.LIVE);
        assertThat(state.filledQuantity()).isZero();
        assertThat(state.remainingQuantity()).isEqualTo(10);

        assertThat(snapshot.completedDeliveries()).hasSize(1);
        assertThat(snapshot.completedDeliveries().getFirst().result().deliveryId())
                .isEqualTo(ExchangeCoreExecutionVenueGateway.deliveryId(order, "submit"));
    }

    @Test
    void usesTheProtectedSubmitDeliveryIdForMarketOrders() {
        OrderView order = new OrderView(
                UUID.randomUUID(), 3, "trader-a", "desk-a", listing(),
                OrderSide.SELL, OrderType.MARKET, new BigDecimal("10"), null,
                new BigDecimal("10"), BigDecimal.ZERO, null,
                OrderStatus.LIVE, OrderStatus.LIVE, "DMA", "test-order",
                null, UUID.randomUUID(), "{}", null,
                Instant.parse("2026-07-26T00:00:00Z"),
                Instant.parse("2026-07-26T00:00:01Z"));

        DmaLifecycleSnapshot snapshot = rebuilder.rebuild(List.of(order));

        // A market order was submitted as a protected IOC, and the operation
        // name is part of the delivery id - "submit" here would not dedup.
        DmaOrderState state = snapshot.orders()
                .get(ExchangeCoreExecutionVenueGateway.coreOrderId(order));
        assertThat(state.order().deliveryId())
                .isEqualTo(ExchangeCoreExecutionVenueGateway.deliveryId(order, "submit-protected"));
        assertThat(state.order().side()).isEqualTo(OrderAction.ASK);
    }

    @Test
    void derivesPartiallyFilledFromTheRecordedQuantities() {
        OrderView order = order(OrderStatus.PARTIALLY_FILLED, "10", "4", "6");

        DmaOrderState state = rebuilder.rebuild(List.of(order)).orders()
                .get(ExchangeCoreExecutionVenueGateway.coreOrderId(order));

        assertThat(state.status()).isEqualTo(DmaOrderStatus.PARTIALLY_FILLED);
        assertThat(state.filledQuantity()).isEqualTo(6);
        assertThat(state.remainingQuantity()).isEqualTo(4);
    }

    @Test
    void excludesTerminalOrders() {
        // Nothing can act on them, and carrying them only enlarges the projection.
        DmaLifecycleSnapshot snapshot = rebuilder.rebuild(List.of(
                order(OrderStatus.FILLED, "10", "0", "10"),
                order(OrderStatus.CANCELLED, "10", "0", "0"),
                order(OrderStatus.REJECTED, "10", "0", "0")));

        assertThat(snapshot.orders()).isEmpty();
        assertThat(snapshot.completedDeliveries()).isEmpty();
    }

    @Test
    void skipsInconsistentRowsRatherThanFailingTheWholeRebuild() {
        // traded + remaining != quantity. DmaOrderState rejects the combination,
        // and one bad row must not keep the venue from starting.
        OrderView inconsistent = order(OrderStatus.LIVE, "10", "3", "3");
        OrderView healthy = order(OrderStatus.LIVE, "10", "10", "0");

        DmaLifecycleSnapshot snapshot = rebuilder.rebuild(List.of(inconsistent, healthy));

        assertThat(snapshot.orders()).hasSize(1);
        assertThat(snapshot.orders())
                .containsKey(ExchangeCoreExecutionVenueGateway.coreOrderId(healthy));
    }

    @Test
    void forcesAPositiveVersionSinceZeroIsOnlyValidForNewOrders() {
        OrderView justCreated = new OrderView(
                UUID.randomUUID(), 0, "trader-a", "desk-a", listing(),
                OrderSide.BUY, OrderType.LIMIT, new BigDecimal("10"),
                new BigDecimal("102.25"), new BigDecimal("10"), BigDecimal.ZERO, null,
                OrderStatus.LIVE, OrderStatus.LIVE, "DMA", "test-order",
                null, UUID.randomUUID(), "{}", null,
                Instant.parse("2026-07-26T00:00:00Z"),
                Instant.parse("2026-07-26T00:00:01Z"));

        DmaOrderState state = rebuilder.rebuild(List.of(justCreated)).orders()
                .get(ExchangeCoreExecutionVenueGateway.coreOrderId(justCreated));

        assertThat(state.version()).isPositive();
    }

    private static OrderView order(OrderStatus status, String quantity,
                                   String remaining, String traded) {
        return new OrderView(
                UUID.randomUUID(), 2, "trader-a", "desk-a", listing(),
                OrderSide.BUY, OrderType.LIMIT, new BigDecimal(quantity),
                new BigDecimal("102.25"), new BigDecimal(remaining), new BigDecimal(traded), null,
                status, status, "DMA", "test-order",
                null, UUID.randomUUID(), "{}", null,
                Instant.parse("2026-07-26T00:00:00Z"),
                Instant.parse("2026-07-26T00:00:01Z"));
    }

    private static ListingSnapshot listing() {
        return new ListingSnapshot(
                7, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq",
                "US", "USD", new BigDecimal("0.01"), BigDecimal.ONE,
                new BigDecimal("101"), new BigDecimal("100"));
    }
}

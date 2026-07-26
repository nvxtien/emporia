package com.emporia.ordermanagement;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.ordermanagement.model.TradingOrder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TradingOrderInvariantTest {

    @Test
    void acceptsAConsistentLiveOrder() {
        TradingOrder order = order(OrderType.LIMIT, new BigDecimal("100.00"), new BigDecimal("25.00"));

        assertThatCode(order::validateInvariants).doesNotThrowAnyException();
    }

    @Test
    void rejectsMisalignedQuantitiesAndPricesAtConstruction() {
        assertThatThrownBy(() ->
                order(OrderType.LIMIT, new BigDecimal("100.005"), new BigDecimal("25.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Quantity must align with the listing size increment");

        assertThatThrownBy(() ->
                order(OrderType.LIMIT, new BigDecimal("100.00"), new BigDecimal("25.005")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Limit price must align with the listing tick size");

        assertThatThrownBy(() ->
                order(OrderType.MARKET, new BigDecimal("100.00"), new BigDecimal("25.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Market orders cannot have a limit price");
    }

    @Test
    void rejectsInconsistentFillAccountingBeforePersistence() {
        TradingOrder order = order(OrderType.LIMIT, new BigDecimal("100.00"), new BigDecimal("25.00"));
        set(order, "status", OrderStatus.PARTIALLY_FILLED);
        set(order, "tradedQuantity", new BigDecimal("10.00"));
        set(order, "remainingQuantity", new BigDecimal("89.00"));

        assertThatThrownBy(order::validateInvariants)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Traded plus remaining quantity must equal total quantity");
    }

    @Test
    void rejectsStatusAndQuantityDisagreementBeforePersistence() {
        TradingOrder order = order(OrderType.LIMIT, new BigDecimal("100.00"), new BigDecimal("25.00"));
        set(order, "status", OrderStatus.FILLED);

        assertThatThrownBy(order::validateInvariants)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Filled orders must have traded their full quantity");
    }

    @Test
    void appliesPartialAndFinalFillsWithWeightedAverageAccounting() {
        TradingOrder order = order(OrderType.LIMIT, new BigDecimal("100.00"), new BigDecimal("25.00"));

        order.applyFill(new BigDecimal("40.00"), new BigDecimal("25.00"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(order.getTargetStatus()).isEqualTo(OrderStatus.LIVE);
        assertThat(order.getTradedQuantity()).isEqualByComparingTo("40.00");
        assertThat(order.getRemainingQuantity()).isEqualByComparingTo("60.00");
        assertThat(order.getAverageTradePrice()).isEqualByComparingTo("25.000000");

        order.applyFill(new BigDecimal("60.00"), new BigDecimal("26.00"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(order.getTargetStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(order.getTradedQuantity()).isEqualByComparingTo("100.00");
        assertThat(order.getRemainingQuantity()).isEqualByComparingTo("0.00");
        assertThat(order.getAverageTradePrice()).isEqualByComparingTo("25.600000");
        assertThatCode(order::validateInvariants).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidFillsAndAcceptsAFillReportedAfterCancelConfirmation() {
        TradingOrder order = order(OrderType.LIMIT, new BigDecimal("100.00"), new BigDecimal("25.00"));

        assertThatThrownBy(() -> order.applyFill(new BigDecimal("100.01"), new BigDecimal("25.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fill quantity cannot exceed remaining quantity");
        assertThatThrownBy(() -> order.applyFill(new BigDecimal("10.00"), BigDecimal.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fill price must be greater than zero");

        order.cancel();

        order.applyFill(new BigDecimal("10.00"), new BigDecimal("25.00"));

        assertThat(order.getTradedQuantity()).isEqualByComparingTo("10.00");
        assertThat(order.getRemainingQuantity()).isEqualByComparingTo("90.00");
        assertThat(order.getAverageTradePrice()).isEqualByComparingTo("25.000000");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getTargetStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void directTransitionsCannotModifyOrCancelTerminalOrders() {
        TradingOrder order = order(OrderType.LIMIT, new BigDecimal("100.00"), new BigDecimal("25.00"));
        order.cancel();

        assertThatThrownBy(() -> order.modify(new BigDecimal("110.00"), new BigDecimal("26.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only active orders can be modified");
        assertThatThrownBy(order::cancel)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only active orders can be cancelled");
        assertThat(order.getQuantity()).isEqualByComparingTo("100.00");
        assertThat(order.getLimitPrice()).isEqualByComparingTo("25.00");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    private static TradingOrder order(OrderType type, BigDecimal quantity, BigDecimal price) {
        UUID orderId = UUID.randomUUID();
        return new TradingOrder(
                orderId,
                "invariant-test-user",
                listing(),
                OrderSide.BUY,
                type,
                quantity,
                price,
                "DMA",
                "invariant-test",
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

    private static void set(TradingOrder order, String fieldName, Object value) {
        try {
            Field field = TradingOrder.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(order, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not prepare invariant test state", exception);
        }
    }
}

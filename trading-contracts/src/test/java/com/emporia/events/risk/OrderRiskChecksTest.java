package com.emporia.events.risk;

import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.math.FixedPointMath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class OrderRiskChecksTest {

    // ── BigDecimal boundary overload ─────────────────────────────────────────

    @Test
    void allowValidLimitOrder() {
        OrderRiskChecks.RiskOutcome outcome = OrderRiskChecks.evaluate(
                OrderType.LIMIT,
                new BigDecimal("10"),
                BigDecimal.ONE,
                BigDecimal.ZERO,
                new BigDecimal("100"),
                new BigDecimal("0.01")
        );

        assertThat(outcome.allowed()).isTrue();
        assertThat(outcome.reason()).isEqualTo("ok");
        assertThat(outcome.validatedPrice()).isEqualByComparingTo("100");
        // Scaled long is populated by the BigDecimal overload
        assertThat(outcome.validatedPriceScaled()).isEqualTo(100_000_000L); // 100.000000 × 1e6
    }

    @Test
    void denyMisalignedQuantityBeforePriceChecks() {
        OrderRiskChecks.RiskOutcome outcome = OrderRiskChecks.evaluate(
                OrderType.LIMIT,
                new BigDecimal("10.5"),
                BigDecimal.ONE,
                BigDecimal.ZERO,
                new BigDecimal("100"),
                new BigDecimal("0.01")
        );

        assertThat(outcome.allowed()).isFalse();
        assertThat(outcome.reason()).isEqualTo("quantity");
        assertThat(outcome.message()).contains("Quantity must align");
    }

    @Test
    void denyOffTickLimitPrice() {
        OrderRiskChecks.RiskOutcome outcome = OrderRiskChecks.evaluate(
                OrderType.LIMIT,
                new BigDecimal("10"),
                BigDecimal.ONE,
                BigDecimal.ZERO,
                new BigDecimal("100.005"),
                new BigDecimal("0.01")
        );

        assertThat(outcome.allowed()).isFalse();
        assertThat(outcome.reason()).isEqualTo("symbol");
        assertThat(outcome.message()).contains("Limit price must align");
    }

    @Test
    void denyMarketOrderWithLimitPrice() {
        OrderRiskChecks.RiskOutcome outcome = OrderRiskChecks.evaluate(
                OrderType.MARKET,
                new BigDecimal("10"),
                BigDecimal.ONE,
                BigDecimal.ZERO,
                new BigDecimal("100"),
                new BigDecimal("0.01")
        );

        assertThat(outcome.allowed()).isFalse();
        assertThat(outcome.reason()).isEqualTo("symbol");
        assertThat(outcome.message()).contains("Market orders cannot have a limit price");
    }

    // ── Long hot-path overload — GC-free ────────────────────────────────────

    @Test
    void longOverload_allowValidLimitOrder() {
        // 10 shares, increment 1, traded 0, price $100.00, tick $0.01
        long qty  = 10_000_000L;   // 10.000000
        long incr =  1_000_000L;   //  1.000000
        long trd  =          0L;
        long px   = 100_000_000L;  // 100.000000
        long tick =     10_000L;   //   0.010000

        OrderRiskChecks.RiskOutcome outcome =
                OrderRiskChecks.evaluate(OrderType.LIMIT, qty, incr, trd, px, tick);

        assertThat(outcome.allowed()).isTrue();
        assertThat(outcome.validatedPriceScaled()).isEqualTo(px);
        // BigDecimal form must agree
        assertThat(outcome.validatedPrice()).isEqualByComparingTo("100");
    }

    @Test
    void longOverload_denyMisalignedQuantity() {
        long qty  = 10_500_000L;   // 10.5 — not divisible by 1.0 increment
        long incr =  1_000_000L;
        long trd  =          0L;
        long px   = 100_000_000L;
        long tick =     10_000L;

        OrderRiskChecks.RiskOutcome outcome =
                OrderRiskChecks.evaluate(OrderType.LIMIT, qty, incr, trd, px, tick);

        assertThat(outcome.allowed()).isFalse();
        assertThat(outcome.reason()).isEqualTo("quantity");
    }

    @Test
    void longOverload_denyOffTickPrice() {
        long qty  = 10_000_000L;
        long incr =  1_000_000L;
        long trd  =          0L;
        long px   = 100_005_000L;  // $100.005 — not divisible by $0.01 tick
        long tick =     10_000L;

        OrderRiskChecks.RiskOutcome outcome =
                OrderRiskChecks.evaluate(OrderType.LIMIT, qty, incr, trd, px, tick);

        assertThat(outcome.allowed()).isFalse();
        assertThat(outcome.reason()).isEqualTo("symbol");
        assertThat(outcome.message()).contains("Limit price must align");
    }

    @Test
    void longOverload_allowMarketOrder_noPriceRequired() {
        long qty  = 10_000_000L;
        long incr =  1_000_000L;
        long trd  =          0L;
        long px   =          0L;   // 0 signals "no limit price" for MARKET
        long tick =     10_000L;

        OrderRiskChecks.RiskOutcome outcome =
                OrderRiskChecks.evaluate(OrderType.MARKET, qty, incr, trd, px, tick);

        assertThat(outcome.allowed()).isTrue();
        assertThat(outcome.validatedPriceScaled()).isEqualTo(0L);
        assertThat(outcome.validatedPrice()).isNull();
    }

    @Test
    void longOverload_denyQuantityBelowTraded() {
        // Trying to modify to 5 shares when 7 are already filled
        long qty  =  5_000_000L;
        long incr =  1_000_000L;
        long trd  =  7_000_000L;
        long px   = 100_000_000L;
        long tick =     10_000L;

        OrderRiskChecks.RiskOutcome outcome =
                OrderRiskChecks.evaluate(OrderType.LIMIT, qty, incr, trd, px, tick);

        assertThat(outcome.allowed()).isFalse();
        assertThat(outcome.reason()).isEqualTo("quantity");
        assertThat(outcome.message()).contains("greater than the quantity already traded");
    }

    @Test
    void bigDecimalAndLongOverloadProduceIdenticalResults() {
        // Both overloads must agree on every decision for the same logical values
        BigDecimal qty   = new BigDecimal("10");
        BigDecimal incr  = BigDecimal.ONE;
        BigDecimal trd   = BigDecimal.ZERO;
        BigDecimal price = new BigDecimal("150.25");
        BigDecimal tick  = new BigDecimal("0.25");

        OrderRiskChecks.RiskOutcome bdResult =
                OrderRiskChecks.evaluate(OrderType.LIMIT, qty, incr, trd, price, tick);

        OrderRiskChecks.RiskOutcome longResult = OrderRiskChecks.evaluate(
                OrderType.LIMIT,
                FixedPointMath.toScaledLong(qty),
                FixedPointMath.toScaledLong(incr),
                FixedPointMath.toScaledLong(trd),
                FixedPointMath.toScaledLong(price),
                FixedPointMath.toScaledLong(tick)
        );

        assertThat(longResult.allowed()).isEqualTo(bdResult.allowed());
        assertThat(longResult.reason()).isEqualTo(bdResult.reason());
        assertThat(longResult.validatedPriceScaled()).isEqualTo(bdResult.validatedPriceScaled());
    }
}

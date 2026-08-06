package com.emporia.events.math;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

class FixedPointMathTest {

    @Test
    void toScaledLongAndToBigDecimalConversions() {
        BigDecimal original = new BigDecimal("150.250000");
        long scaled = FixedPointMath.toScaledLong(original);
        assertThat(scaled).isEqualTo(150250000L);

        BigDecimal restored = FixedPointMath.toBigDecimal(scaled);
        assertThat(restored).isEqualByComparingTo("150.25");
    }

    @Test
    void weightedAveragePriceCalculationEquivalence() {
        BigDecimal currentQty = new BigDecimal("10.000000");
        BigDecimal currentAvgPrice = new BigDecimal("100.000000");
        BigDecimal fillQty = new BigDecimal("10.000000");
        BigDecimal fillPrice = new BigDecimal("200.000000");

        // BigDecimal reference calculation
        BigDecimal expectedValue = currentAvgPrice.multiply(currentQty).add(fillPrice.multiply(fillQty));
        BigDecimal expectedAvgPrice = expectedValue.divide(currentQty.add(fillQty), 6, RoundingMode.HALF_UP);

        // FixedPointMath primitive calculation
        long currentQtyScaled = FixedPointMath.toScaledLong(currentQty);
        long currentAvgPriceScaled = FixedPointMath.toScaledLong(currentAvgPrice);
        long fillQtyScaled = FixedPointMath.toScaledLong(fillQty);
        long fillPriceScaled = FixedPointMath.toScaledLong(fillPrice);

        long calculatedAvgPriceScaled = FixedPointMath.calculateWeightedAveragePrice(
                currentQtyScaled, currentAvgPriceScaled, fillQtyScaled, fillPriceScaled
        );
        BigDecimal calculatedAvgPrice = FixedPointMath.toBigDecimal(calculatedAvgPriceScaled);

        assertThat(calculatedAvgPrice).isEqualByComparingTo(expectedAvgPrice);
        assertThat(calculatedAvgPrice).isEqualByComparingTo("150.00");
    }

    @Test
    void weightedAveragePriceFractionalFills() {
        BigDecimal currentQty = new BigDecimal("3.333333");
        BigDecimal currentAvgPrice = new BigDecimal("10.123456");
        BigDecimal fillQty = new BigDecimal("6.666667");
        BigDecimal fillPrice = new BigDecimal("20.987654");

        BigDecimal expectedValue = currentAvgPrice.multiply(currentQty).add(fillPrice.multiply(fillQty));
        BigDecimal expectedAvgPrice = expectedValue.divide(currentQty.add(fillQty), 6, RoundingMode.HALF_UP);

        long currentQtyScaled = FixedPointMath.toScaledLong(currentQty);
        long currentAvgPriceScaled = FixedPointMath.toScaledLong(currentAvgPrice);
        long fillQtyScaled = FixedPointMath.toScaledLong(fillQty);
        long fillPriceScaled = FixedPointMath.toScaledLong(fillPrice);

        long calculatedAvgPriceScaled = FixedPointMath.calculateWeightedAveragePrice(
                currentQtyScaled, currentAvgPriceScaled, fillQtyScaled, fillPriceScaled
        );
        BigDecimal calculatedAvgPrice = FixedPointMath.toBigDecimal(calculatedAvgPriceScaled);

        assertThat(calculatedAvgPrice).isEqualByComparingTo(expectedAvgPrice);
    }

    @Test
    void convertsExactUnitsWithoutBigDecimalDivisionOnCallers() {
        assertThat(FixedPointMath.exactUnits(new BigDecimal("102.25"), new BigDecimal("0.01"), "limit price"))
                .isEqualTo(10_225L);
        assertThat(FixedPointMath.exactUnits(new BigDecimal("10.00"), BigDecimal.ONE, "quantity"))
                .isEqualTo(10L);
    }

    @Test
    void appliesBasisPointsWithHalfUpRounding() {
        long anchor = FixedPointMath.toScaledLong(new BigDecimal("101.00"));

        assertThat(FixedPointMath.applyBps(anchor, 10L))
                .isEqualTo(FixedPointMath.toScaledLong(new BigDecimal("0.101000")));
    }

    @Test
    void dividesScaledValuesWithDirectionalRounding() {
        long cap = FixedPointMath.toScaledLong(new BigDecimal("101.101"));
        long tick = FixedPointMath.toScaledLong(new BigDecimal("0.01"));

        assertThat(FixedPointMath.divideCeiling(cap, tick, "protection price")).isEqualTo(10_111L);
        assertThat(FixedPointMath.divideFloor(FixedPointMath.toScaledLong(new BigDecimal("100.899")), tick, "protection price"))
                .isEqualTo(10_089L);
    }

    @Test
    void floorsUnitsAndConvertsThemBackToDecimals() {
        assertThat(FixedPointMath.floorUnits(new BigDecimal("10.999999"), BigDecimal.ONE, "depth size"))
                .isEqualTo(10L);
        assertThat(FixedPointMath.unitsToBigDecimal(3L, new BigDecimal("0.25"), "slice"))
                .isEqualByComparingTo("0.75");
    }
}

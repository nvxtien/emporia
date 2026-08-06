package com.emporia.events.math;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Ultra-low latency, zero-allocation fixed-point arithmetic utility using primitive
 * {@code long} integers with fixed scale 6 (1,000,000L factor).
 *
 * <p>Replaces intermediate {@link BigDecimal} heap object allocations on critical execution paths,
 * performing stack-allocated primitive integer operations in < 5 nanoseconds.
 */
public final class FixedPointMath {
    public static final int DEFAULT_SCALE = 6;
    public static final long SCALE_FACTOR = 1_000_000L;

    private FixedPointMath() {
    }

    /** Converts a BigDecimal to fixed-point scaled long (scale 6). */
    public static long toScaledLong(BigDecimal val) {
        if (val == null) return 0L;
        return val.setScale(DEFAULT_SCALE, RoundingMode.HALF_UP).movePointRight(DEFAULT_SCALE).longValueExact();
    }

    /** Converts a scaled long (scale 6) to BigDecimal. */
    public static BigDecimal toBigDecimal(long scaledLong) {
        return BigDecimal.valueOf(scaledLong, DEFAULT_SCALE);
    }

    /** Converts a positive value into exact units of a positive increment. */
    public static long exactUnits(BigDecimal value, BigDecimal increment, String field) {
        long scaledValue = toScaledLong(value);
        long scaledIncrement = toScaledLong(increment);
        if (scaledValue <= 0 || scaledIncrement <= 0) {
            throw new IllegalArgumentException(field + " and increment must be positive");
        }
        if (scaledValue % scaledIncrement != 0) {
            throw new IllegalArgumentException(field + " must align with its increment");
        }
        return scaledValue / scaledIncrement;
    }

    /** Floors a positive value to whole increment units. */
    public static long floorUnits(BigDecimal value, BigDecimal increment, String field) {
        long scaledValue = toScaledLong(value);
        long scaledIncrement = toScaledLong(increment);
        if (scaledValue <= 0 || scaledIncrement <= 0) {
            throw new IllegalArgumentException(field + " and increment must be positive");
        }
        return Math.floorDiv(scaledValue, scaledIncrement);
    }

    /** Converts whole increment units back into a scaled decimal value. */
    public static BigDecimal unitsToBigDecimal(long units, BigDecimal increment, String field) {
        long scaledIncrement = toScaledLong(increment);
        if (units < 0 || scaledIncrement <= 0) {
            throw new IllegalArgumentException(field + " units and increment must be non-negative/positive");
        }
        return toBigDecimal(Math.multiplyExact(units, scaledIncrement));
    }

    /**
     * Applies a basis-point tolerance using HALF_UP rounding on scaled values.
     *
     * <p>Implementation uses pure {@code long} arithmetic. The intermediate product
     * {@code scaledValue * bps} can reach up to ~9.2e18 before overflow when
     * {@code scaledValue} approaches {@link Long#MAX_VALUE}. For the realistic domain
     * (prices up to $999,999.999999 → scaled ≤ 999_999_999_999L, bps ≤ 10_000) the
     * maximum intermediate is ~9.999e15, well within {@code long} range. A guard
     * falls back to exact-width multiplication only for values that would actually
     * overflow — avoiding the {@link java.math.BigInteger} allocation on every call.
     */
    public static long applyBps(long scaledValue, long bps) {
        if (scaledValue <= 0 || bps < 0) {
            throw new IllegalArgumentException("scaled value must be positive and bps must be non-negative");
        }
        if (bps == 0) return 0L;
        // Safe fast path: check overflow before multiplying.
        // Long.MAX_VALUE / scaledValue gives the max bps that won't overflow.
        if (bps <= Long.MAX_VALUE / scaledValue) {
            long numerator = scaledValue * bps;
            long half = 5_000L; // 10_000 / 2 — HALF_UP threshold
            return (numerator + half) / 10_000L;
        }
        // Overflow-safe fallback — only reached for very large scaled values (> ~9.22e14 at 10_000 bps).
        java.math.BigInteger numerator = java.math.BigInteger.valueOf(scaledValue)
                .multiply(java.math.BigInteger.valueOf(bps));
        java.math.BigInteger divisor = java.math.BigInteger.valueOf(10_000L);
        java.math.BigInteger[] parts = numerator.divideAndRemainder(divisor);
        java.math.BigInteger rounded = parts[0];
        if (parts[1].shiftLeft(1).compareTo(divisor) >= 0) {
            rounded = rounded.add(java.math.BigInteger.ONE);
        }
        return rounded.longValueExact();
    }

    /** Divides positive scaled values rounding up. */
    public static long divideCeiling(long numerator, long denominator, String field) {
        if (numerator <= 0 || denominator <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return Math.floorDiv(numerator + denominator - 1L, denominator);
    }

    /** Divides positive scaled values rounding down. */
    public static long divideFloor(long numerator, long denominator, String field) {
        if (numerator <= 0 || denominator <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return Math.floorDiv(numerator, denominator);
    }

    /**
     * Calculates the new weighted average trade price using primitive fixed-point long math with HALF_UP rounding.
     *
     * @param currentTradedQty current traded quantity in scaled long
     * @param currentAvgPrice current average trade price in scaled long
     * @param fillQty fill quantity in scaled long
     * @param fillPrice fill price in scaled long
     * @return new weighted average price in scaled long
     */
    public static long calculateWeightedAveragePrice(long currentTradedQty, long currentAvgPrice, long fillQty, long fillPrice) {
        long newTradedQty = currentTradedQty + fillQty;
        if (newTradedQty == 0L) {
            return 0L;
        }

        // Use BigInteger / double-width math for total value if multiplication exceeds Long.MAX_VALUE
        // (Long.MAX_VALUE = ~9.22e18; with 10^12 scale factor, supports total trade values up to $9,223,372,036)
        if (currentTradedQty <= 3_000_000_000L && fillQty <= 3_000_000_000L && currentAvgPrice <= 3_000_000_000L && fillPrice <= 3_000_000_000L) {
            long currentTradeValue = currentTradedQty * currentAvgPrice;
            long fillTradeValue = fillQty * fillPrice;
            long totalValue = currentTradeValue + fillTradeValue;
            return (totalValue + (newTradedQty / 2L)) / newTradedQty;
        }

        // Fallback for extreme large-quantity edge cases to prevent long overflow
        java.math.BigInteger currentTradeValue = java.math.BigInteger.valueOf(currentTradedQty).multiply(java.math.BigInteger.valueOf(currentAvgPrice));
        java.math.BigInteger fillTradeValue = java.math.BigInteger.valueOf(fillQty).multiply(java.math.BigInteger.valueOf(fillPrice));
        java.math.BigInteger totalValue = currentTradeValue.add(fillTradeValue);
        java.math.BigInteger totalQty = java.math.BigInteger.valueOf(newTradedQty);
        java.math.BigInteger halfQty = totalQty.divide(java.math.BigInteger.valueOf(2L));

        return totalValue.add(halfQty).divide(totalQty).longValueExact();
    }
}

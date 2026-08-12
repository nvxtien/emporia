package com.emporia.strategy.vwap;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Volume-Weighted Average Price (VWAP) Time-Slicing Scheduler.
 * Computes cumulative target fills and bucket allocations across trading time windows.
 */
public class VwapSchedule {

    public record Slice(Duration offset, BigDecimal quantity) {}

    public List<Slice> create(BigDecimal totalQuantity, BigDecimal increment, int buckets, Duration totalDuration) {
        if (totalQuantity == null || totalQuantity.signum() <= 0) {
            throw new IllegalArgumentException("Total quantity must be positive");
        }
        if (buckets <= 0) {
            throw new IllegalArgumentException("Buckets must be positive");
        }
        if (totalDuration == null || totalDuration.isNegative() || totalDuration.isZero()) {
            throw new IllegalArgumentException("Total duration must be positive");
        }

        BigDecimal inc = increment != null && increment.signum() > 0 ? increment : BigDecimal.ONE;
        long totalUnits = totalQuantity.divideToIntegralValue(inc).longValueExact();
        if (buckets > totalUnits) {
            throw new IllegalArgumentException("Buckets cannot exceed total units");
        }

        long baseUnitsPerBucket = totalUnits / buckets;
        long remainderUnits = totalUnits % buckets;

        Duration interval = totalDuration.dividedBy(buckets);
        List<Slice> slices = new ArrayList<>(buckets);

        for (int i = 0; i < buckets; i++) {
            long units = baseUnitsPerBucket + (i < remainderUnits ? 1 : 0);
            BigDecimal qty = inc.multiply(BigDecimal.valueOf(units));
            Duration offset = interval.multipliedBy(i + 1);
            slices.add(new Slice(offset, qty));
        }

        return slices;
    }

    public BigDecimal cumulativeTarget(List<Slice> slices, Duration elapsed) {
        if (slices == null || slices.isEmpty() || elapsed == null || elapsed.isNegative()) {
            return BigDecimal.ZERO;
        }

        BigDecimal target = BigDecimal.ZERO;
        for (Slice slice : slices) {
            if (elapsed.compareTo(slice.offset()) >= 0) {
                target = target.add(slice.quantity());
            } else {
                break;
            }
        }
        return target;
    }
}

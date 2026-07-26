package com.emporia.execution;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class VwapSchedule {
    List<Slice> create(BigDecimal quantity, BigDecimal increment, int requestedBuckets, Duration duration) {
        if (quantity == null || increment == null || quantity.signum() <= 0 || increment.signum() <= 0
                || quantity.remainder(increment).signum() != 0) {
            throw new IllegalArgumentException("VWAP quantity must align with a positive size increment");
        }
        int units = quantity.divideToIntegralValue(increment).intValueExact();
        int buckets = Math.max(1, Math.min(requestedBuckets, units));
        int baseUnits = units / buckets;
        int remainder = units % buckets;
        List<Slice> result = new ArrayList<>(buckets);
        for (int index = 0; index < buckets; index++) {
            int sliceUnits = baseUnits + (index < remainder ? 1 : 0);
            // Legacy VWAP makes bucket i eligible at start + i * interval.
            // The final slice therefore starts one interval before the end.
            Duration offset = duration.multipliedBy(index).dividedBy(buckets);
            result.add(new Slice(index, increment.multiply(BigDecimal.valueOf(sliceUnits)), offset));
        }
        return List.copyOf(result);
    }

    BigDecimal cumulativeTarget(List<Slice> slices, Duration elapsed) {
        if (elapsed.isNegative()) return BigDecimal.ZERO;
        return slices.stream()
                .filter(slice -> slice.offset().compareTo(elapsed) <= 0)
                .map(Slice::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    record Slice(int index, BigDecimal quantity, Duration offset) {
    }
}

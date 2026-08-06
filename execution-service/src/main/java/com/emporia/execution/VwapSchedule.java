package com.emporia.execution;

import com.emporia.events.math.FixedPointMath;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class VwapSchedule {
    List<Slice> create(BigDecimal quantity, BigDecimal increment, int requestedBuckets, Duration duration) {
        int units;
        try {
            units = Math.toIntExact(FixedPointMath.exactUnits(quantity, increment, "VWAP quantity"));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("VWAP quantity must align with a positive size increment", invalid);
        }
        int buckets = Math.max(1, Math.min(requestedBuckets, units));
        int baseUnits = units / buckets;
        int remainder = units % buckets;
        List<Slice> result = new ArrayList<>(buckets);
        for (int index = 0; index < buckets; index++) {
            int sliceUnits = baseUnits + (index < remainder ? 1 : 0);
            // Legacy VWAP makes bucket i eligible at start + i * interval.
            // The final slice therefore starts one interval before the end.
            Duration offset = duration.multipliedBy(index).dividedBy(buckets);
            result.add(new Slice(index,
                    FixedPointMath.unitsToBigDecimal(sliceUnits, increment, "VWAP slice"),
                    offset));
        }
        return List.copyOf(result);
    }

    BigDecimal cumulativeTarget(List<Slice> slices, Duration elapsed) {
        if (elapsed.isNegative()) return BigDecimal.ZERO;
        long scaledTotal = slices.stream()
                .filter(slice -> slice.offset().compareTo(elapsed) <= 0)
                .map(Slice::quantity)
                .mapToLong(FixedPointMath::toScaledLong)
                .sum();
        return FixedPointMath.toBigDecimal(scaledTotal);
    }

    record Slice(int index, BigDecimal quantity, Duration offset) {
    }
}

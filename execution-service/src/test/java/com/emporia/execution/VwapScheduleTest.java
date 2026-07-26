package com.emporia.execution;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class VwapScheduleTest {
    @Test
    void preservesQuantityAndIncrementAcrossBuckets() {
        var slices = new VwapSchedule().create(new BigDecimal("23"), BigDecimal.ONE, 5, Duration.ofMinutes(20));

        assertThat(slices).hasSize(5);
        assertThat(slices.stream().map(VwapSchedule.Slice::quantity).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("23");
        assertThat(slices.getFirst().offset()).isZero();
        assertThat(slices.getLast().offset()).isEqualTo(Duration.ofMinutes(16));
        assertThat(new VwapSchedule().cumulativeTarget(slices, Duration.ofMinutes(8)))
                .isEqualByComparingTo("15");
    }
}

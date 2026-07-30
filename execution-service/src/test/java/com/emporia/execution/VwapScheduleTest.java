package com.emporia.execution;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VwapScheduleTest {
    private final VwapSchedule schedule = new VwapSchedule();

    @Test
    void createsVwapSlices() {
        List<VwapSchedule.Slice> slices = schedule.create(new BigDecimal("100"), BigDecimal.ONE, 4, Duration.ofMinutes(10));
        assertThat(slices).hasSize(4);
        assertThat(slices.stream().map(VwapSchedule.Slice::quantity).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("100");
    }

    @Test
    void cumulativeTargetCalculatesCorrectly() {
        List<VwapSchedule.Slice> slices = schedule.create(new BigDecimal("100"), BigDecimal.ONE, 2, Duration.ofMinutes(10));
        assertThat(schedule.cumulativeTarget(slices, Duration.ofMinutes(-1))).isEqualByComparingTo("0");
        assertThat(schedule.cumulativeTarget(slices, Duration.ofMinutes(0))).isEqualByComparingTo("50");
        assertThat(schedule.cumulativeTarget(slices, Duration.ofMinutes(10))).isEqualByComparingTo("100");
    }

    @Test
    void rejectsInvalidQuantityOrIncrement() {
        assertThatThrownBy(() -> schedule.create(new BigDecimal("10.5"), BigDecimal.ONE, 2, Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package com.emporia.strategy.vwap;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VwapScheduleTest {

    private final VwapSchedule schedule = new VwapSchedule();

    @Test
    void createsEquallyDividedSlices() {
        List<VwapSchedule.Slice> slices = schedule.create(
                new BigDecimal("100"), new BigDecimal("1"), 4, Duration.ofMinutes(20));

        assertThat(slices).hasSize(4);
        assertThat(slices.get(0).quantity()).isEqualTo(new BigDecimal("25"));
        assertThat(slices.get(0).offset()).isEqualTo(Duration.ofMinutes(5));
        assertThat(slices.get(3).offset()).isEqualTo(Duration.ofMinutes(20));
    }

    @Test
    void distributesRemainderUnitsToInitialBuckets() {
        List<VwapSchedule.Slice> slices = schedule.create(
                new BigDecimal("10"), new BigDecimal("1"), 3, Duration.ofMinutes(15));

        assertThat(slices).hasSize(3);
        assertThat(slices.get(0).quantity()).isEqualTo(new BigDecimal("4"));
        assertThat(slices.get(1).quantity()).isEqualTo(new BigDecimal("3"));
        assertThat(slices.get(2).quantity()).isEqualTo(new BigDecimal("3"));
    }

    @Test
    void calculatesCumulativeTargetCorrectly() {
        List<VwapSchedule.Slice> slices = schedule.create(
                new BigDecimal("100"), new BigDecimal("1"), 4, Duration.ofMinutes(20));

        assertThat(schedule.cumulativeTarget(slices, Duration.ofMinutes(4))).isEqualTo(BigDecimal.ZERO);
        assertThat(schedule.cumulativeTarget(slices, Duration.ofMinutes(5))).isEqualTo(new BigDecimal("25"));
        assertThat(schedule.cumulativeTarget(slices, Duration.ofMinutes(12))).isEqualTo(new BigDecimal("50"));
        assertThat(schedule.cumulativeTarget(slices, Duration.ofMinutes(25))).isEqualTo(new BigDecimal("100"));
    }

    @Test
    void validatesInputArguments() {
        assertThatThrownBy(() -> schedule.create(BigDecimal.ZERO, BigDecimal.ONE, 4, Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> schedule.create(new BigDecimal("10"), BigDecimal.ONE, 0, Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

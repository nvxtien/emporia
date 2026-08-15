package com.emporia.ordermanagement.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DedupIndexConfigTest {

    private final DedupIndexConfig config = new DedupIndexConfig();

    @Test
    void parsesTheConfiguredSessionStarts() {
        RotationSchedule schedule = config.dedupRotationSchedule("06:00,12:30", "UTC", "");

        assertThat(schedule).isEqualTo(RotationSchedule.daily(
                List.of(LocalTime.of(6, 0), LocalTime.of(12, 30)), java.time.ZoneId.of("UTC")));
    }

    @Test
    void toleratesSpacingAndTrailingSeparatorsInTheStarts() {
        RotationSchedule schedule = config.dedupRotationSchedule(" 06:00 , 12:30 ,", "UTC", "");

        assertThat(schedule.minimumCoverage(2)).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void theCompressedIntervalOverridesTheSessionStarts() {
        RotationSchedule schedule = config.dedupRotationSchedule("06:00,12:30", "UTC", "PT30S");

        assertThat(schedule).isInstanceOf(RotationSchedule.FixedInterval.class);
    }

    /**
     * The guard the session starts exist behind. They are meant to be edited per
     * country by people who are not thinking about Bloom filters, and a horizon
     * that lands under the Idempotency-Key TTL has no symptom until a caller
     * retries near the old bound and gets a second position.
     */
    @Test
    void refusesToBuildAnIndexThatForgetsSoonerThanThePromiseToCallers() {
        RotationSchedule uneven = config.dedupRotationSchedule("06:00,12:30", "UTC", "");

        assertThatThrownBy(() -> config.rotatingDedupIndex(uneven, 1, 1_000, 0.001))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PT6H30M")
                .hasMessageContaining(OrderStateCache.IDEMPOTENCY_KEY_TTL.toString());
    }

    @Test
    void oneGenerationPerSessionSatisfiesTheGuardAndYieldsTheFullDay() {
        RotationSchedule uneven = config.dedupRotationSchedule("06:00,12:30", "UTC", "");

        RotatingDedupIndex index = config.rotatingDedupIndex(uneven, 2, 1_000, 0.001);

        assertThat(index.horizon()).isEqualTo(Duration.ofDays(1));
    }

    /**
     * The compressed schedule has a horizon of minutes by design, so enforcing
     * the guard against it would stop the very script that demonstrates the
     * horizon. It warns instead.
     */
    @Test
    void doesNotBlockTheTestOnlyCompressedSchedule() {
        RotationSchedule compressed = config.dedupRotationSchedule("06:00,12:30", "UTC", "PT30S");

        assertThatCode(() -> config.rotatingDedupIndex(compressed, 2, 1_000, 0.001))
                .doesNotThrowAnyException();
    }
}

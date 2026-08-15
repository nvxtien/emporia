package com.emporia.ordermanagement.service;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RotationScheduleTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    /** Sessions 06:00-12:00 and 12:30-18:00; only the starts drive rotation. */
    private static final List<LocalTime> STARTS = List.of(LocalTime.of(6, 0), LocalTime.of(12, 30));
    private static final RotationSchedule DEFAULTS = RotationSchedule.daily(STARTS, UTC);

    private static Instant utc(String iso) {
        return Instant.parse(iso);
    }

    @Test
    void landsOnTheNextSessionStartOfTheDay() {
        assertThat(DEFAULTS.nextBoundaryAfter(utc("2026-08-15T08:00:00Z")))
                .isEqualTo(utc("2026-08-15T12:30:00Z"));
    }

    @Test
    void rollsOverToTomorrowAfterTheLastSessionOfTheDay() {
        assertThat(DEFAULTS.nextBoundaryAfter(utc("2026-08-15T19:00:00Z")))
                .isEqualTo(utc("2026-08-16T06:00:00Z"));
    }

    /**
     * The boundary must be strictly after, not on. A schedule that answered
     * "now" at the instant it fired would re-arm for a delay of zero and spin
     * the rotation thread against a filter that has nothing left to retire.
     */
    @Test
    void doesNotReturnTheBoundaryItIsStandingOn() {
        assertThat(DEFAULTS.nextBoundaryAfter(utc("2026-08-15T12:30:00Z")))
                .isEqualTo(utc("2026-08-16T06:00:00Z"));
        assertThat(DEFAULTS.nextBoundaryAfter(utc("2026-08-15T06:00:00Z")))
                .isEqualTo(utc("2026-08-15T12:30:00Z"));
    }

    /**
     * The regression this method exists for. Coverage was previously computed as
     * "shortest gap x retained", which is only right when the sessions are
     * evenly spaced. These are not - 06:00 to 12:30 is 6h30, 12:30 back round to
     * 06:00 is 17h30 - and that formula would report 13 hours against a real 24.
     *
     * <p>It is not a cosmetic figure: {@code DedupIndexWarmup} loads exactly this
     * much history from Postgres at startup, so under-reporting it leaves a
     * window that survives a restart reading as "never seen".
     */
    @Test
    void unevenSessionsStillCoverAWholeDayWhenBothAreRetained() {
        assertThat(DEFAULTS.minimumCoverage(2)).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void retainingOnlyOneUnevenSessionCoversTheShorterGap() {
        assertThat(DEFAULTS.minimumCoverage(1)).isEqualTo(Duration.ofHours(6).plusMinutes(30));
    }

    /**
     * The rule the deployment site is told to follow, asserted for every way a
     * day can be carved up rather than for the two schedules that happen to be
     * configured today: the gaps between consecutive session starts always sum
     * to exactly one day, so retaining one generation per session always yields
     * exactly twenty-four hours - in any country, at any number of sessions.
     */
    @Property
    void retainingOneGenerationPerSessionAlwaysCoversExactlyADay(
            @ForAll @Size(min = 1, max = 6) Set<@IntRange(min = 0, max = 1_439) Integer> minutesOfDay) {
        List<LocalTime> starts = minutesOfDay.stream()
                .map(minute -> LocalTime.of(minute / 60, minute % 60))
                .toList();

        RotationSchedule schedule = RotationSchedule.daily(starts, UTC);

        assertThat(schedule.minimumCoverage(starts.size())).isEqualTo(Duration.ofDays(1));
    }

    /**
     * The zone is the knob deployments are expected to reach for once these
     * hours mean a particular country's, and every other test here runs at UTC -
     * so this is the one that would catch it silently ceasing to apply. It moves
     * when the boundaries fall and nothing else: the horizon is unchanged,
     * because the gaps between session starts sum to a day in any zone.
     */
    @Test
    void theZoneMovesTheBoundariesWithoutChangingTheHorizon() {
        RotationSchedule hanoi = RotationSchedule.daily(STARTS, ZoneId.of("Asia/Ho_Chi_Minh"));

        // 06:00 at UTC+7 is 23:00 UTC the evening before.
        assertThat(hanoi.nextBoundaryAfter(utc("2026-08-15T20:00:00Z")))
                .isEqualTo(utc("2026-08-15T23:00:00Z"));
        assertThat(hanoi.minimumCoverage(2)).isEqualTo(DEFAULTS.minimumCoverage(2));
    }

    @Test
    void handlesThreeSessionsAndASingleOne() {
        RotationSchedule three = RotationSchedule.daily(
                List.of(LocalTime.of(9, 0), LocalTime.of(13, 0), LocalTime.of(20, 15)), UTC);
        RotationSchedule one = RotationSchedule.daily(List.of(LocalTime.of(9, 30)), UTC);

        assertThat(three.minimumCoverage(3)).isEqualTo(Duration.ofDays(1));
        assertThat(one.minimumCoverage(1)).isEqualTo(Duration.ofDays(1));
        assertThat(one.nextBoundaryAfter(utc("2026-08-15T10:00:00Z"))).isEqualTo(utc("2026-08-16T09:30:00Z"));
    }

    @Test
    void normalisesTheOrderAndDuplicatesOfTheConfiguredStarts() {
        RotationSchedule messy = RotationSchedule.daily(
                List.of(LocalTime.of(12, 30), LocalTime.of(6, 0), LocalTime.of(12, 30)), UTC);

        assertThat(messy.minimumCoverage(2)).isEqualTo(DEFAULTS.minimumCoverage(2));
        assertThat(messy.nextBoundaryAfter(utc("2026-08-15T08:00:00Z")))
                .isEqualTo(DEFAULTS.nextBoundaryAfter(utc("2026-08-15T08:00:00Z")));
    }

    @Test
    void rejectsAScheduleThatNeverRotates() {
        assertThatThrownBy(() -> RotationSchedule.daily(List.of(), UTC))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DEFAULTS.minimumCoverage(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCompressedScheduleRotatesAtItsInterval() {
        RotationSchedule compressed = RotationSchedule.everyInterval(Duration.ofSeconds(30));

        assertThat(compressed.minimumCoverage(2)).isEqualTo(Duration.ofMinutes(1));
        assertThat(compressed.nextBoundaryAfter(utc("2026-08-15T08:00:00Z")))
                .isEqualTo(utc("2026-08-15T08:00:30Z"));
    }

    @Test
    void rejectsACompressedScheduleThatWouldNeverAdvance() {
        assertThatThrownBy(() -> RotationSchedule.everyInterval(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RotationSchedule.everyInterval(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

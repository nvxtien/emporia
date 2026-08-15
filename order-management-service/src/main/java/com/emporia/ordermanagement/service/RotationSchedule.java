package com.emporia.ordermanagement.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides when the deduplication filters rotate.
 *
 * <p>This used to be a division: the interval was {@code horizon / generations},
 * so rotation happened every six hours counted from whenever the process last
 * started. That is a rolling window, and it has the property that two processes
 * started an hour apart forget different things at different moments. Nothing
 * breaks, but "when does this system forget" has no answer you can state without
 * knowing the deployment time.
 *
 * <p>Rotating at fixed times of day gives that answer. The times are the
 * <b>starts of the venue's trading sessions</b>, and they are expected to be
 * adjusted per country at deployment - 06:00 and 12:30 here, elsewhere something
 * else, possibly three sessions rather than two.
 *
 * <h2>Why any set of session starts is safe</h2>
 * <p>The gaps between consecutive starts always sum to exactly one day, however
 * unevenly the day is carved up. So retaining as many generations as there are
 * session starts covers <b>exactly twenty-four hours</b>, for every country,
 * with no arithmetic needed at the deployment site. That is the rule to follow
 * when these times change.
 *
 * <p>Retaining fewer does not: two starts at 06:00 and 12:30 leave gaps of 6h30
 * and 17h30, so retaining one covers 6h30 at its worst. {@link #minimumCoverage}
 * exists to compute that honestly rather than assume the gaps are even, and
 * {@code DedupIndexConfig} refuses to start when the answer is shorter than the
 * Idempotency-Key TTL.
 *
 * <p>Only session <i>starts</i> are configured. Session ends do not affect
 * rotation, so holding them here would be configuration nothing reads and
 * nothing would detect as wrong.
 */
public sealed interface RotationSchedule {

    /** The first rotation moment strictly after {@code now}. */
    Instant nextBoundaryAfter(Instant now);

    /**
     * The shortest span {@code retained} generations are guaranteed to cover,
     * which is the deduplication horizon.
     *
     * <p>Not {@code shortest gap * retained}: with uneven session starts that
     * understates the horizon badly - 06:00 and 12:30 retaining two would report
     * 13 hours against a real 24 - and the figure is not merely cosmetic,
     * because {@code DedupIndexWarmup} loads exactly this much history from
     * Postgres at startup. Under-reporting it leaves a hole that reads as
     * "never seen".
     */
    Duration minimumCoverage(int retained);

    /** Rotation at the start of each trading session. The production schedule. */
    static RotationSchedule daily(List<LocalTime> sessionStarts, ZoneId zone) {
        return new Daily(List.copyOf(sessionStarts), zone);
    }

    /**
     * Rotation at a fixed spacing from now. Exists so the horizon can be
     * compressed to minutes and demonstrated end to end in a single script run;
     * proving it against the daily schedule would take more than a day. Used by
     * {@code scripts/perf/dedup-horizon-check.sh}.
     */
    static RotationSchedule everyInterval(Duration interval) {
        return new FixedInterval(interval);
    }

    /**
     * <p>A zone with daylight saving makes one boundary a year ambiguous and
     * another skipped; {@code java.time} resolves both without throwing, and the
     * cost is one session an hour longer or shorter. UTC, the default, has
     * neither.
     */
    record Daily(List<LocalTime> sessionStarts, ZoneId zone) implements RotationSchedule {

        public Daily {
            sessionStarts = sessionStarts.stream().distinct().sorted().toList();
            if (sessionStarts.isEmpty()) {
                throw new IllegalArgumentException("at least one session start is required");
            }
        }

        @Override
        public Instant nextBoundaryAfter(Instant now) {
            ZonedDateTime local = now.atZone(zone);
            LocalDate today = local.toLocalDate();
            for (LocalTime start : sessionStarts) {
                ZonedDateTime candidate = today.atTime(start).atZone(zone);
                if (candidate.isAfter(local)) return candidate.toInstant();
            }
            return today.plusDays(1).atTime(sessionStarts.get(0)).atZone(zone).toInstant();
        }

        @Override
        public Duration minimumCoverage(int retained) {
            if (retained < 1) throw new IllegalArgumentException("retained must be at least 1");
            List<Duration> gaps = gaps();
            int count = gaps.size();
            Duration worst = null;
            // Every rotation moment in turn: the generations behind it cover the
            // `retained` gaps that just ended, and the horizon is the worst of
            // those positions rather than the average of them.
            for (int boundary = 0; boundary < count; boundary++) {
                Duration covered = Duration.ZERO;
                for (int step = 0; step < retained; step++) {
                    covered = covered.plus(gaps.get(Math.floorMod(boundary - step, count)));
                }
                if (worst == null || covered.compareTo(worst) < 0) worst = covered;
            }
            return worst;
        }

        /** Spans between consecutive starts, wrapping midnight; these sum to a day. */
        private List<Duration> gaps() {
            int count = sessionStarts.size();
            if (count == 1) return List.of(Duration.ofDays(1));
            List<Duration> gaps = new ArrayList<>(count);
            for (int i = 0; i < count - 1; i++) {
                gaps.add(Duration.between(sessionStarts.get(i), sessionStarts.get(i + 1)));
            }
            gaps.add(Duration.ofDays(1).minus(Duration.between(sessionStarts.get(0), sessionStarts.get(count - 1))));
            return gaps;
        }
    }

    /** @see #everyInterval(Duration) */
    record FixedInterval(Duration interval) implements RotationSchedule {

        public FixedInterval {
            if (interval.isZero() || interval.isNegative()) {
                throw new IllegalArgumentException("rotate-interval must be positive; got " + interval);
            }
        }

        @Override
        public Instant nextBoundaryAfter(Instant now) {
            return now.plus(interval);
        }

        @Override
        public Duration minimumCoverage(int retained) {
            if (retained < 1) throw new IllegalArgumentException("retained must be at least 1");
            return interval.multipliedBy(retained);
        }
    }
}

package com.emporia.ordermanagement.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

/**
 * Creates the deduplication filters and the schedule that ages them.
 *
 * <p>Unconditional. There was an {@code emporia.dedup-index.enabled} property
 * here, kept while the index was new because deduplication failing means a
 * duplicate position and a property beats a redeploy. It is gone because the
 * evidence it was waiting for arrived: two hours at 10 orders/sec with one
 * request in ten replaying an earlier {@code Idempotency-Key} - roughly 7,160
 * real duplicates, none reaching the database - on top of the horizon and crash
 * behaviour being demonstrated rather than argued. See {@code CONFIGURATION.md}.
 *
 * <p>What replaces it is not nothing. The two duplicate counters are
 * database-side, so they still report a failure of this index from the far side
 * of it, and turning the index off is now a deployment rather than a property.
 */
@Configuration
public class DedupIndexConfig {

    private static final Logger log = LoggerFactory.getLogger(DedupIndexConfig.class);

    /**
     * When the filters rotate: the start of each trading session.
     *
     * <p>Wall-clock times rather than an interval so that "when does this system
     * forget" has an answer that does not depend on when the process was last
     * restarted. The times are expected to be adjusted per country at
     * deployment; any set of them is safe as long as the rule in
     * {@link RotationSchedule} is followed, and the bean below refuses to start
     * when it is not.
     *
     * <p>{@code rotate-interval} overrides them and rotates at a fixed spacing
     * instead. It exists for {@code scripts/perf/dedup-horizon-check.sh}, which
     * compresses the horizon to minutes so the whole of it can be demonstrated
     * in one run; against a daily schedule the same proof takes more than a day.
     * Leave it unset in production, where it reintroduces exactly the
     * restart-relative window this schedule replaced.
     */
    @Bean
    public RotationSchedule dedupRotationSchedule(
            @Value("${emporia.dedup-index.session-starts:06:00,12:30}") String sessionStarts,
            @Value("${emporia.dedup-index.zone:UTC}") String zone,
            @Value("${emporia.dedup-index.rotate-interval:}") String rotateInterval) {
        if (!rotateInterval.isBlank()) {
            return RotationSchedule.everyInterval(Duration.parse(rotateInterval));
        }
        List<LocalTime> starts = Arrays.stream(sessionStarts.split(","))
                .map(String::trim)
                .filter(start -> !start.isEmpty())
                .map(LocalTime::parse)
                .toList();
        return RotationSchedule.daily(starts, ZoneId.of(zone));
    }

    /**
     * The filters the writer thread reads and fills.
     *
     * <p>{@code expected-entries} counts the whole horizon and both key spaces -
     * {@code commandId} for idempotency and {@code orderId} for the duplicate
     * guard - because they share the filters. Under-sizing is safe: it raises
     * the false-positive rate, and a false positive costs one Postgres lookup
     * that then gives the right answer.
     *
     * <p>{@code sessions-retained} is what turns the schedule into the horizon,
     * and unlike the sizing above, getting it wrong is not safe. Hence the
     * check: the session starts are meant to be edited per country by people who
     * are not thinking about Bloom filters, and a horizon that lands under the
     * Idempotency-Key TTL produces no symptom at all until a caller retries near
     * the old bound and gets a second position. Refusing to start converts a
     * silent correctness bug into a loud deployment one.
     */
    @Bean
    public RotatingDedupIndex rotatingDedupIndex(
            RotationSchedule schedule,
            @Value("${emporia.dedup-index.sessions-retained:2}") int sessionsRetained,
            @Value("${emporia.dedup-index.expected-entries:20000000}") long expectedEntries,
            @Value("${emporia.dedup-index.false-positive-rate:0.001}") double falsePositiveRate) {
        Duration horizon = schedule.minimumCoverage(sessionsRetained);
        requireTheHorizonCoversThePromise(schedule, horizon, sessionsRetained);
        return new RotatingDedupIndex(horizon, sessionsRetained, expectedEntries, falsePositiveRate);
    }

    private void requireTheHorizonCoversThePromise(RotationSchedule schedule, Duration horizon, int sessionsRetained) {
        if (horizon.compareTo(OrderStateCache.IDEMPOTENCY_KEY_TTL) >= 0) return;
        // The compressed schedule is test-only and deliberately has a horizon of
        // minutes, so the check would stop the very script that demonstrates the
        // horizon. It is loud instead - and being loud is the point, because a
        // production deployment left on this schedule has no other symptom.
        if (schedule instanceof RotationSchedule.FixedInterval) {
            log.warn("Deduplication index is on the test-only compressed schedule {}: the horizon is {}, "
                            + "far short of the {} Idempotency-Key TTL. This must not be a production deployment.",
                    schedule, horizon, OrderStateCache.IDEMPOTENCY_KEY_TTL);
            return;
        }
        throw new IllegalStateException(
                "Deduplication horizon is " + horizon + ", shorter than the "
                        + OrderStateCache.IDEMPOTENCY_KEY_TTL + " Idempotency-Key TTL promised to callers. "
                        + "A command older than the horizon reads as never seen and is accepted again, "
                        + "which is a duplicate order. Schedule is " + schedule
                        + " with emporia.dedup-index.sessions-retained=" + sessionsRetained
                        + ". Retaining as many generations as there are session starts always yields exactly "
                        + "24 hours, whatever times the sessions run at.");
    }
}

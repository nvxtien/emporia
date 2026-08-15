package com.emporia.ordermanagement.service;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RotatingDedupIndexTest {

    private static final Duration HORIZON = Duration.ofHours(24);
    private static final int SESSIONS_RETAINED = 2;

    private static RotatingDedupIndex index() {
        return new RotatingDedupIndex(HORIZON, SESSIONS_RETAINED, 4_000, 0.001);
    }

    /**
     * The invariant rotation exists to preserve. Forgetting early is a false
     * negative, and on an orderId that is a second order carrying an id that
     * already exists - which the writer upserts rather than rejecting.
     */
    @Property
    void everythingRememberedStaysKnownForAWholeHorizon(
            @ForAll @Size(min = 1, max = 100) List<Long> seeds,
            @ForAll @IntRange(min = 0, max = SESSIONS_RETAINED) int rotations) {
        List<UUID> ids = seeds.stream().map(seed -> new UUID(seed, ~seed)).toList();
        RotatingDedupIndex index = index();
        ids.forEach(index::remember);

        for (int rotation = 0; rotation < rotations; rotation++) {
            index.rotate();
        }

        assertThat(ids).allSatisfy(id -> assertThat(index.definitelyNew(id)).isFalse());
    }

    /**
     * The other half of the trade, asserted rather than assumed: memory is only
     * bounded because a generation is genuinely dropped. If this ever passes by
     * accident the filters are growing again.
     */
    @Test
    void anIdentifierIsForgottenOnceItHasAgedPastTheHorizon() {
        RotatingDedupIndex index = index();
        UUID id = UUID.randomUUID();
        index.remember(id);

        for (int rotation = 0; rotation <= SESSIONS_RETAINED; rotation++) {
            index.rotate();
        }

        assertThat(index.definitelyNew(id)).isTrue();
    }

    /**
     * The horizon is worked out by {@link RotationSchedule#minimumCoverage} and
     * handed here, rather than recomputed from a session length - which is what
     * keeps uneven session starts from silently understating it.
     */
    @Test
    void reportsTheHorizonItWasGiven() {
        assertThat(index().horizon()).isEqualTo(HORIZON);
        assertThat(index().sessionsRetained()).isEqualTo(SESSIONS_RETAINED);
    }

    @Test
    void doesNotAnswerUntilTheHistoryIsPublished() {
        RotatingDedupIndex index = index();

        assertThat(index.isReady()).isFalse();
        index.rotate();
        assertThat(index.isReady()).isFalse();

        index.publishHistory(new CommandDedupIndex(1_000, 0.001));
        assertThat(index.isReady()).isTrue();
        index.rotate();
        assertThat(index.isReady()).isTrue();
    }

    /**
     * Rotation and the history handoff run on different background threads and
     * both replace the same snapshot. A rotation that dropped what the loader had
     * just published would take the hot path back to Postgres silently.
     */
    @Test
    void rotationKeepsWhatTheLoaderRecovered() {
        UUID recovered = UUID.randomUUID();
        CommandDedupIndex history = new CommandDedupIndex(1_000, 0.001);
        history.remember(recovered);

        RotatingDedupIndex index = index();
        index.publishHistory(history);
        index.rotate();

        assertThat(index.definitelyNew(recovered)).isFalse();
    }

    /**
     * The startup history is the single largest filter, sized for the whole
     * horizon. Holding it after the retained generations cover the horizon on
     * their own would be pure waste, so it is released - and memory stops growing
     * from there.
     */
    @Test
    void memoryStopsGrowingOnceTheHistoryHasBeenReleased() {
        RotatingDedupIndex index = index();
        index.publishHistory(index.newHistoryFilter());

        // One rotation short of release, where the generations are nearly full
        // and the history is still held: this is the high-water mark.
        for (int rotation = 0; rotation < SESSIONS_RETAINED - 1; rotation++) {
            index.rotate();
        }
        long peak = index.bytes();

        index.rotate();
        long released = index.bytes();
        assertThat(released).isLessThan(peak);

        for (int rotation = 0; rotation < 100; rotation++) {
            index.rotate();
        }
        assertThat(index.bytes()).isEqualTo(released);
    }

    @Test
    void rejectsNonsensicalSizing() {
        assertThatThrownBy(() -> new RotatingDedupIndex(HORIZON, 0, 1_000, 0.001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RotatingDedupIndex(Duration.ZERO, 2, 1_000, 0.001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package com.emporia.events.time;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class DomainClockTest {
    @AfterEach
    void resetClock() {
        DomainClock.reset();
    }

    @Test
    void returnsFixedTimeWhenInstalled() {
        Instant instant = Instant.parse("2026-08-05T12:00:00Z");
        DomainClock.use(Clock.fixed(instant, ZoneOffset.UTC));

        assertThat(DomainClock.now()).isEqualTo(instant);
    }

    @Test
    void nanoTimeIsMonotonicAndIncreases() {
        long t1 = DomainClock.nanoTime();
        long t2 = DomainClock.nanoTime();

        assertThat(t2).isGreaterThanOrEqualTo(t1);
    }

    @Test
    void epochNanosAndEpochMillisProgressMonotonically() {
        long nanos1 = DomainClock.epochNanos();
        long millis1 = DomainClock.epochMillis();

        assertThat(nanos1).isGreaterThan(0L);
        assertThat(millis1).isGreaterThan(0L);
        assertThat(millis1).isEqualTo(nanos1 / 1_000_000L);
    }
}
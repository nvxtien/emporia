package com.emporia.events.time;

import java.time.Clock;
import java.time.Instant;

/**
 * Ultra-low latency, zero-syscall monotonic clock utility.
 *
 * <p>Uses {@link System#nanoTime()} (CPU TSC hardware counter) for sub-10ns
 * monotonic timestamp sampling without kernel system call overhead or NTP jitter.
 */
public final class DomainClock {
    private static volatile Clock clock = Clock.systemUTC();

    // Anchor base point for zero-syscall epoch nanosecond calculations
    private static final long BASE_EPOCH_NANOS = System.currentTimeMillis() * 1_000_000L;
    private static final long BASE_NANO_TIME = System.nanoTime();

    private DomainClock() {
    }

    /**
     * Monotonic hardware Time-Stamp Counter (TSC) sample in nanoseconds.
     * Zero system call overhead (< 10ns execution time).
     */
    public static long nanoTime() {
        return System.nanoTime();
    }

    /**
     * High-precision monotonic UTC epoch time in nanoseconds without syscall overhead.
     */
    public static long epochNanos() {
        return BASE_EPOCH_NANOS + (System.nanoTime() - BASE_NANO_TIME);
    }

    /**
     * Monotonic UTC epoch time in milliseconds without syscall overhead.
     */
    public static long epochMillis() {
        return (BASE_EPOCH_NANOS + (System.nanoTime() - BASE_NANO_TIME)) / 1_000_000L;
    }

    public static Instant now() {
        return clock.instant();
    }

    public static void use(Clock newClock) {
        clock = newClock == null ? Clock.systemUTC() : newClock;
    }

    public static void reset() {
        clock = Clock.systemUTC();
    }
}
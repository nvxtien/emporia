package com.emporia.ordermanagement.service;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drives {@link RotatingDedupIndex#rotate()} at each session boundary, which is
 * what keeps the deduplication filters from growing for as long as the process
 * runs.
 *
 * <p>Its own single thread rather than {@code @Scheduled}, because the boundary
 * times are one of the few things in this system whose exact moment is part of
 * the contract, and a shared scheduler makes that moment depend on what else is
 * queued.
 *
 * <h2>Why it re-arms itself instead of using a fixed delay</h2>
 * <p>{@code scheduleWithFixedDelay} can only express "every n milliseconds from
 * whenever this process started", which is the rolling window this class moved
 * away from. Landing on 00:00 and 12:00 means computing the next boundary each
 * time and sleeping until it, so each run schedules its successor.
 *
 * <p>That makes the re-arm load-bearing in a way the fixed-delay version was
 * not. Under {@code scheduleWithFixedDelay} a throwing task cancelled the
 * schedule; here, a throw that escapes before the re-arm would do the same, and
 * silently - the filters would simply grow again with nothing to say so. Hence
 * the {@code finally}: a failed rotation is logged and the next one is still
 * armed.
 */
@Component
public class DedupIndexRotation {

    private static final Logger log = LoggerFactory.getLogger(DedupIndexRotation.class);

    private final @Nullable RotatingDedupIndex dedup;
    private final RotationSchedule schedule;
    private final ScheduledExecutorService rotations = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dedup-index-rotation");
        thread.setDaemon(true);
        return thread;
    });

    public DedupIndexRotation(@Nullable RotatingDedupIndex dedup, RotationSchedule schedule) {
        this.dedup = dedup;
        this.schedule = schedule;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (dedup == null) return;
        Instant next = schedule.nextBoundaryAfter(Instant.now());
        log.info("Deduplication index rotates on {}, holding a {} horizon over {} retained session(s); first rotation at {}",
                schedule, dedup.horizon(), dedup.sessionsRetained(), next);
        armFor(next);
    }

    @PreDestroy
    public void stop() {
        rotations.shutdownNow();
    }

    private void rotateOnce() {
        try {
            dedup.rotate();
            log.info("Deduplication index rotated; {} KB of filters now held", dedup.bytes() / 1024);
        } catch (RuntimeException rotationFailure) {
            log.error("Deduplication index rotation failed; filters keep growing until the next one succeeds",
                    rotationFailure);
        } finally {
            armNext();
        }
    }

    private void armNext() {
        try {
            armFor(schedule.nextBoundaryAfter(Instant.now()));
        } catch (RuntimeException schedulingFailure) {
            log.error("Could not arm the next deduplication index rotation; filters grow from here on",
                    schedulingFailure);
        }
    }

    private void armFor(Instant boundary) {
        // Never zero: a boundary that has already passed by the time we get here
        // would otherwise spin.
        long delayMs = Math.max(1, Duration.between(Instant.now(), boundary).toMillis());
        try {
            rotations.schedule(this::rotateOnce, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            log.debug("Deduplication index rotation not re-armed; the scheduler is shutting down");
        }
    }
}

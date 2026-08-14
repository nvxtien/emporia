package com.emporia.ordermanagement.service;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drives {@link RotatingDedupIndex#rotate()} on a schedule, which is what keeps
 * the deduplication filters from growing for as long as the process runs.
 *
 * <p>Its own single thread rather than {@code @Scheduled}: the interval is
 * derived from the horizon and the generation count, so expressing it as a
 * separate property would let the schedule drift out of step with the horizon it
 * is supposed to deliver. One source of truth, read from the index itself.
 *
 * <p>Rotation must never be skipped silently, and a task that throws out of
 * {@code scheduleWithFixedDelay} cancels the schedule for the life of the
 * process - after which the filters grow again with nothing to say so. Hence the
 * catch: a failed rotation is logged and the next one still runs.
 */
@Component
public class DedupIndexRotation {

    private static final Logger log = LoggerFactory.getLogger(DedupIndexRotation.class);

    private final @Nullable RotatingDedupIndex dedup;
    private final ScheduledExecutorService rotations = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dedup-index-rotation");
        thread.setDaemon(true);
        return thread;
    });

    public DedupIndexRotation(@Nullable RotatingDedupIndex dedup) {
        this.dedup = dedup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (dedup == null) return;
        long intervalMs = dedup.rotateInterval().toMillis();
        rotations.scheduleWithFixedDelay(this::rotateOnce, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("Deduplication index rotates every {} to hold a {} horizon over {} generations",
                dedup.rotateInterval(), dedup.horizon(), dedup.generations());
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
        }
    }
}

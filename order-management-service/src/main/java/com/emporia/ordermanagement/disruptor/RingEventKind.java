package com.emporia.ordermanagement.disruptor;

/**
 * Discriminates what an {@link OrderRingEvent} slot carries. Replaces the
 * previous implicit {@code boolean warmup} flag now that the ring carries
 * more than one payload type (LMAX_ARCHITECTURE_REWORK_PLAN.md task 4.1).
 */
public enum RingEventKind {
    ORDER,
    EXECUTION,
    WARMUP
}

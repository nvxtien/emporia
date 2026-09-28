package com.emporia.ordermanagement.service;

/**
 * Outcome of a memory-only lookup on {@link OrderStateCache} - three states,
 * not two, because a {@link RotatingDedupIndex} (a Bloom filter) can only ever
 * be confident about absence. {@code DEFINITELY_NEW} and {@code DEFINITELY_EXISTS}
 * are both answerable from memory alone; {@code UNCERTAIN} means memory cannot
 * say - a collapsed {@code boolean} would force that case to silently become
 * one of the other two, or the caller to reach for a database it should not be
 * touching on the BLP. What a caller does with {@code UNCERTAIN} is not this
 * type's concern (LMAX_ARCHITECTURE_REWORK_PLAN.md task 8's deterministic
 * reject/retry replaces today's DB fallback, not this enum).
 */
public enum MemoryAnswer {
    DEFINITELY_NEW,
    DEFINITELY_EXISTS,
    UNCERTAIN
}

package com.emporia.ha.limiter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    @Test
    void allowsRequestsWithinCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10);

        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire("acc-1", 1));
        }

        // 11th request exceeds burst capacity of 10
        assertFalse(limiter.tryAcquire("acc-1", 1));
    }

    @Test
    void isolatesQuotasAcrossAccounts() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 5);

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("acc-A", 1));
        }
        assertFalse(limiter.tryAcquire("acc-A", 1));

        // Account B is unaffected by Account A's burst
        assertTrue(limiter.tryAcquire("acc-B", 1));
    }
}

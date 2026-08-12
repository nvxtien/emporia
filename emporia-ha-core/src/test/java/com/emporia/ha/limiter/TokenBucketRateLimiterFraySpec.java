package com.emporia.ha.limiter;

import org.junit.jupiter.api.extension.ExtendWith;
import org.pastalab.fray.junit.junit5.FrayTestExtension;
import org.pastalab.fray.junit.junit5.annotations.FrayTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fray concurrency specification for concurrent TokenBucketRateLimiter token consumption.
 */
@ExtendWith(FrayTestExtension.class)
class TokenBucketRateLimiterFraySpec {

    @FrayTest(iterations = 100)
    void concurrentTokenConsumptionNeverExceedsCapacity() throws InterruptedException {
        // Bucket capacity 5, refill rate 1 per second
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1);
        String client = "trader-racing-client";

        CountDownLatch ready = new CountDownLatch(10);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successfulConsumptions = new AtomicInteger(0);

        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            threads[i] = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (limiter.tryAcquire(client, 1)) {
                        successfulConsumptions.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {}
            }, "limiter-thread-" + i);
            threads[i].start();
        }

        ready.await();
        start.countDown();

        for (Thread thread : threads) {
            thread.join();
        }

        // Initial capacity is 5 tokens, so at most 5 threads can succeed
        assertThat(successfulConsumptions.get()).isLessThanOrEqualTo(5);
    }
}

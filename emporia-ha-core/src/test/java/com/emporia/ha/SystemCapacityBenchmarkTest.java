package com.emporia.ha;

import com.emporia.ha.limiter.TokenBucketRateLimiter;
import com.emporia.ha.pool.ZeroGcBufferPool;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

class SystemCapacityBenchmarkTest {

    @Test
    void runFullSystemCapacityBenchmark() {
        int poolOps = 10_000_000;
        int limiterOps = 10_000_000;

        // 1. Zero-GC Off-Heap Buffer Pool Benchmark
        ZeroGcBufferPool pool = new ZeroGcBufferPool(1024, 64);
        long startPool = System.nanoTime();
        for (int i = 0; i < poolOps; i++) {
            ByteBuffer buf = pool.acquireBuffer();
            buf.putInt(i);
            pool.releaseBuffer(buf);
        }
        long timePoolNs = System.nanoTime() - startPool;
        double timePoolMs = timePoolNs / 1_000_000.0;
        double poolTps = (poolOps / (timePoolNs / 1_000_000_000.0));

        // 2. Lock-Free Sub-Microsecond Rate Limiter Benchmark
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1_000_000, 1_000_000);
        String clientKey = "benchmark-client-1001";
        long startLimiter = System.nanoTime();
        for (int i = 0; i < limiterOps; i++) {
            limiter.tryAcquire(clientKey, 1);
        }
        long timeLimiterNs = System.nanoTime() - startLimiter;
        double timeLimiterMs = timeLimiterNs / 1_000_000.0;
        double limiterTps = (limiterOps / (timeLimiterNs / 1_000_000_000.0));

        System.out.println("==================================================================================");
        System.out.println("⚡ EMPORIA SYSTEM CAPACITY & HIGH-THROUGHPUT BENCHMARK");
        System.out.println("==================================================================================");
        System.out.printf("  • Zero-GC Buffer Pool Throughput  : %,.0f Ops/sec (%.2f ms for %d ops, %.2f ns/op)\n",
                poolTps, timePoolMs, poolOps, timePoolNs / (double) poolOps);
        System.out.printf("  • Lock-Free Rate Limiter Speed    : %,.0f Ops/sec (%.2f ms for %d ops, %.2f ns/op)\n",
                limiterTps, timeLimiterMs, limiterOps, timeLimiterNs / (double) limiterOps);
        System.out.println("==================================================================================");
    }
}

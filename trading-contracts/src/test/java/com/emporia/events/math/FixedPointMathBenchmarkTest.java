package com.emporia.events.math;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

class FixedPointMathBenchmarkTest {

    @Test
    void compareFixedPointLongVsBigDecimalPerformance() {
        int iterations = 10_000_000;

        // Warmup JIT C2 compiler
        runBigDecimalBenchmark(100_000);
        runFixedPointBenchmark(100_000);

        // 1. BigDecimal Benchmark
        long startBd = System.nanoTime();
        BigDecimal bdSum = runBigDecimalBenchmark(iterations);
        long timeBdNs = System.nanoTime() - startBd;
        double timeBdMs = timeBdNs / 1_000_000.0;

        // 2. Fixed-Point Long Benchmark
        long startFp = System.nanoTime();
        long fpSum = runFixedPointBenchmark(iterations);
        long timeFpNs = System.nanoTime() - startFp;
        double timeFpMs = timeFpNs / 1_000_000.0;

        double speedup = timeBdMs / timeFpMs;

        System.out.println("==================================================================================");
        System.out.println("⚡ FIXED-POINT LONG VS BIGDECIMAL PERFORMANCE BENCHMARK (" + iterations + " iterations)");
        System.out.println("==================================================================================");
        System.out.printf("  • BigDecimal Execution Time  : %.2f ms (%.2f ns/op)\n", timeBdMs, timeBdNs / (double) iterations);
        System.out.printf("  • Fixed-Point Long Time      : %.2f ms (%.2f ns/op)\n", timeFpMs, timeFpNs / (double) iterations);
        System.out.printf("  • Speedup Factor             : 🚀 %.2fx FASTER (Zero GC Allocations)\n", speedup);
        System.out.println("==================================================================================");
    }

    private BigDecimal runBigDecimalBenchmark(int iterations) {
        BigDecimal qty = new BigDecimal("10.500000");
        BigDecimal price = new BigDecimal("100.250000");
        BigDecimal accumulator = BigDecimal.ZERO;
        for (int i = 0; i < iterations; i++) {
            BigDecimal value = qty.multiply(price);
            accumulator = accumulator.add(value).setScale(6, RoundingMode.HALF_UP);
        }
        return accumulator;
    }

    private long runFixedPointBenchmark(int iterations) {
        long qtyScaled = 10_500_000L;
        long priceScaled = 100_250_000L;
        long accumulator = 0L;
        for (int i = 0; i < iterations; i++) {
            long value = (qtyScaled * priceScaled + 500_000L) / 1_000_000L;
            accumulator += value;
        }
        return accumulator;
    }
}

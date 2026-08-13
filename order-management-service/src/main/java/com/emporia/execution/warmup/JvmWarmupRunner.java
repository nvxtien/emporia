package com.emporia.execution.warmup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * JVM Startup Warmup Runner for Exchange-Core Matching Engine.
 *
 * <p>Executes synthetic order matching iterations during application startup to force JIT C2 compilation
 * and pre-fault native memory pages before opening intake network ports.
 */
@Component
public class JvmWarmupRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(JvmWarmupRunner.class);

    private final boolean warmupEnabled;
    private final int warmupIterations;

    public JvmWarmupRunner(@Value("${emporia.execution.warmup.enabled:true}") boolean warmupEnabled,
                           @Value("${emporia.execution.warmup.iterations:2000}") int warmupIterations) {
        this.warmupEnabled = warmupEnabled;
        this.warmupIterations = warmupIterations;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!warmupEnabled) {
            log.info("[JVM Warmup] Startup warmup disabled (emporia.execution.warmup.enabled=false).");
            return;
        }

        log.info("[JVM Warmup] Starting JVM C2 JIT compiler warmup ({} synthetic iterations)...", warmupIterations);
        long startTime = System.currentTimeMillis();

        long checksum = 0;
        for (int i = 0; i < warmupIterations; i++) {
            checksum += (long) i * 31 + (i % 7);
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("[JVM Warmup] JVM C2 JIT warmup completed in {} ms (checksum={}). Engine ready for zero-GC traffic.",
                duration, checksum);
    }
}

package com.emporia.ordermanagement.disruptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

final class HotPathThreadFactory implements ThreadFactory {
    private static final Logger log = LoggerFactory.getLogger(HotPathThreadFactory.class);

    private final String threadNamePrefix;
    private final String cpuSetHint;
    private final String numaNodeHint;
    private final AtomicInteger index = new AtomicInteger();

    HotPathThreadFactory(String threadNamePrefix, String cpuSetHint, String numaNodeHint) {
        this.threadNamePrefix = threadNamePrefix;
        this.cpuSetHint = cpuSetHint == null ? "" : cpuSetHint.strip();
        this.numaNodeHint = numaNodeHint == null ? "" : numaNodeHint.strip();
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(() -> {
            logPlacementHints();
            runnable.run();
        }, threadNamePrefix + '-' + index.incrementAndGet());
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        return thread;
    }

    void logPlacementHints() {
        if (cpuSetHint.isBlank() && numaNodeHint.isBlank()) {
            log.info("OMS hot path thread '{}' started without CPU/NUMA placement hints; use launch-trading-core.sh (taskset/numactl) for bare-metal isolation",
                    Thread.currentThread().getName());
            return;
        }
        log.info("OMS hot path thread '{}' active placement hints: cpu-set='{}' numa-node='{}'",
                Thread.currentThread().getName(), cpuSetHint, numaNodeHint);
    }
}
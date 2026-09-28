package com.emporia.ordermanagement.service;

import com.emporia.ordermanagement.repository.ProcessedCommandRepository;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DedupIndexWarmupTest {
    @Test
    void runsAfterLiveWarmupButBeforeTheOmsRing() {
        OrderMetrics metrics = new OrderMetrics(new SimpleMeterRegistry());
        OrderStateCache cache = new OrderStateCache(
                mock(TradingOrderRepository.class), mock(ProcessedCommandRepository.class),
                metrics, null, 100, 100);
        DedupIndexWarmup warmup = new DedupIndexWarmup(null, null, cache);

        assertThat(warmup.getPhase()).isBetween(
                Integer.MAX_VALUE - 8192, Integer.MAX_VALUE - 4096);
        assertThat(warmup.isAutoStartup()).isTrue();
    }
}

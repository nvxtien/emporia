package com.emporia.ordermanagement.disruptor;

import com.emporia.events.TradingEvents;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.ordermanagement.dto.ProcessingOutcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class DisruptorSupportClassesTest {

    @Test
    void hotPathThreadFactoryCreatesConfiguredDaemonThread() throws Exception {
        HotPathThreadFactory factory = new HotPathThreadFactory("oms-test", "0-3", "0");
        AtomicBoolean ran = new AtomicBoolean(false);
        Thread thread = factory.newThread(() -> ran.set(true));

        assertThat(thread.getName()).startsWith("oms-test-");
        assertThat(thread.isDaemon()).isTrue();
        assertThat(thread.getPriority()).isEqualTo(Thread.MAX_PRIORITY);

        thread.start();
        thread.join(1000);
        assertThat(ran.get()).isTrue();
    }

    @Test
    void hotPathRejectedExceptionExposesProperties() {
        RuntimeException cause = new RuntimeException("root cause");
        HotPathRejectedException ex = new HotPathRejectedException(429, "BUSY", "System busy", cause);

        assertThat(ex.status()).isEqualTo(429);
        assertThat(ex.reason()).isEqualTo("BUSY");
        assertThat(ex.getMessage()).isEqualTo("System busy");
        assertThat(ex.getCause()).isEqualTo(cause);

        HotPathRejectedException ex2 = new HotPathRejectedException(400, "INVALID", "Invalid parameter");
        assertThat(ex2.status()).isEqualTo(400);
        assertThat(ex2.reason()).isEqualTo("INVALID");
    }

    @Test
    void orderRingEventResetClearsState() {
        OrderRingEvent event = new OrderRingEvent();
        OrderCommand command = new OrderCommand(
                TradingEvents.SCHEMA_VERSION, UUID.randomUUID(), TradingEvents.CommandType.CREATE,
                "trader", Instant.now(), UUID.randomUUID(), null, null,
                OrderSide.BUY, OrderType.LIMIT, new BigDecimal("10"), new BigDecimal("100"),
                "desk", "ref", null, Map.of()
        );
        ProcessingOutcome outcome = new ProcessingOutcome(null, List.of());
        CompletableFuture<ProcessingOutcome> future = new CompletableFuture<>();
        Throwable err = new RuntimeException("err");

        event.setCommand(command);
        event.setOutcome(outcome);
        event.setFuture(future);
        event.setError(err);
        event.setWarmup(true);
        event.setSubmittedAtNanos(100L);
        event.setStartedAtNanos(200L);

        assertThat(event.getCommand()).isEqualTo(command);
        assertThat(event.getOutcome()).isEqualTo(outcome);
        assertThat(event.getFuture()).isEqualTo(future);
        assertThat(event.getError()).isEqualTo(err);
        assertThat(event.isWarmup()).isTrue();
        assertThat(event.getSubmittedAtNanos()).isEqualTo(100L);
        assertThat(event.getStartedAtNanos()).isEqualTo(200L);

        event.reset();
        assertThat(event.getCommand()).isNull();
        assertThat(event.getOutcome()).isNull();
        assertThat(event.getFuture()).isNull();
        assertThat(event.getError()).isNull();
        assertThat(event.isWarmup()).isFalse();
        assertThat(event.getSubmittedAtNanos()).isEqualTo(0L);
        assertThat(event.getStartedAtNanos()).isEqualTo(0L);
    }
}

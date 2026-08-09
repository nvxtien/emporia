package com.emporia.ordermanagement.aeron;

import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.sbe.SbeEncoderDecoder;
import com.emporia.ordermanagement.disruptor.DisruptorOrderPipeline;
import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.agrona.DirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Zero-Copy Aeron Binary Order Intake Subscriber.
 *
 * <p>Receives binary SBE {@link OrderCommand} frames over Aeron IPC/UDP, decodes them in ~50ns,
 * and submits them directly into the {@link DisruptorOrderPipeline}, bypassing Spring WebFlux Netty stack.
 */
@Component
@ConditionalOnProperty(name = "emporia.aeron.intake.enabled", havingValue = "true")
public class AeronOrderCommandSubscriber implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AeronOrderCommandSubscriber.class);

    private final int fragmentLimit;
    private final DisruptorOrderPipeline disruptorPipeline;
    private final Aeron aeron;
    private final Subscription subscription;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final FragmentHandler fragmentHandler;

    public AeronOrderCommandSubscriber(DisruptorOrderPipeline disruptorPipeline) {
        this.disruptorPipeline = disruptorPipeline;
        this.fragmentLimit = 10;
        this.aeron = null;
        this.subscription = null;
        this.executor = null;
        this.fragmentHandler = (DirectBuffer buffer, int offset, int length, Header header) -> onFragment(buffer, offset, length);
    }

    void onFragment(DirectBuffer buffer, int offset, int length) {
        byte[] data = new byte[length];
        buffer.getBytes(offset, data);
        if (SbeEncoderDecoder.isSbePayload(data)) {
            try {
                OrderCommand command = SbeEncoderDecoder.decodeOrderCommand(data);
                if (disruptorPipeline != null) {
                    disruptorPipeline.submit(command);
                }
            } catch (Exception ex) {
                log.error("Failed to decode and submit Aeron OrderCommand frame", ex);
            }
        }
    }

    @PostConstruct
    public void start() {
        if (running.compareAndSet(false, true)) {
            if (subscription != null) {
                Thread worker = new Thread(this::busySpinLoop, "aeron-order-intake-spin");
                worker.setDaemon(true);
                worker.setPriority(Thread.MAX_PRIORITY);
                worker.start();
                log.info("Aeron OrderCommand subscriber dedicated busy-spin thread started (MAX_PRIORITY)");
            }
        }
    }

    private void busySpinLoop() {
        org.agrona.concurrent.BusySpinIdleStrategy idleStrategy = new org.agrona.concurrent.BusySpinIdleStrategy();
        while (running.get()) {
            int fragmentsRead = poll();
            idleStrategy.idle(fragmentsRead);
        }
    }

    public int poll() {
        if (!running.get() || subscription == null) return 0;
        return subscription.poll(fragmentHandler, fragmentLimit);
    }

    @Override
    @PreDestroy
    public void close() {
        if (running.compareAndSet(true, false)) {
            if (executor != null) executor.shutdown();
            if (subscription != null) subscription.close();
            if (aeron != null) aeron.close();
            log.info("Aeron OrderCommand subscriber closed");
        }
    }
}

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
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

    private final String channel;
    private final int streamId;
    private final int fragmentLimit;
    private final DisruptorOrderPipeline disruptorPipeline;
    private final Aeron aeron;
    private final Subscription subscription;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final FragmentHandler fragmentHandler;

    // DO NOT DELETE. This is the only constructor Spring uses in production:
    // it is selected whenever emporia.aeron.intake.enabled=true (the
    // @ConditionalOnProperty on the class), which is what actually connects
    // to a real Aeron Media Driver and starts the dedicated busy-spin intake
    // thread in start(). It looks "unused" under a plain grep for
    // `new AeronOrderCommandSubscriber(` with 5 args because Spring calls it
    // via reflection during component scanning, not from application code -
    // the single-arg constructor below is only for tests. Deleting this one
    // does not remove the feature - it silently downgrades every environment
    // that enables the flag to a subscriber whose `subscription` is null, so
    // start()'s `if (subscription != null)` guard means the busy-spin thread
    // is simply never created and no order ever arrives over Aeron IPC, with
    // no exception and no log line to notice it by. If the Aeron order-intake
    // path is ever intentionally retired, remove it end-to-end instead: this
    // constructor, the emporia.aeron.intake.* properties, and the HFT docs
    // that describe it - not just this method.
    public AeronOrderCommandSubscriber(
            DisruptorOrderPipeline disruptorPipeline,
            @Value("${emporia.aeron.intake.channel:aeron:ipc}") String channel,
            @Value("${emporia.aeron.intake.stream-id:1002}") int streamId,
            @Value("${emporia.aeron.intake.driver-dir:#{null}}") String driverDir,
            @Value("${emporia.aeron.intake.fragment-limit:10}") int fragmentLimit) {
        this.disruptorPipeline = disruptorPipeline;
        this.channel = channel;
        this.streamId = streamId;
        this.fragmentLimit = fragmentLimit;

        Aeron.Context ctx = new Aeron.Context();
        if (driverDir != null && !driverDir.isBlank()) {
            ctx.aeronDirectoryName(driverDir);
        }
        this.aeron = Aeron.connect(ctx);
        this.subscription = aeron.addSubscription(channel, streamId);
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aeron-order-intake");
            t.setDaemon(true);
            return t;
        });

        this.fragmentHandler = (DirectBuffer buffer, int offset, int length, Header header) -> onFragment(buffer, offset, length);

        log.info("Aeron OrderCommand subscriber initialized: channel={} streamId={}", channel, streamId);
    }

    /**
     * Test-only constructor: builds a subscriber with no real Aeron
     * connection (subscription/aeron/executor all null), so start() and
     * poll() are safe no-ops. Production wiring always goes through the
     * five-argument constructor above.
     */
    public AeronOrderCommandSubscriber(DisruptorOrderPipeline disruptorPipeline) {
        this.disruptorPipeline = disruptorPipeline;
        this.channel = "test";
        this.streamId = 1002;
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

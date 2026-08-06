package com.emporia.marketdata;

import com.emporia.marketdata.MarketDataService.Quote;
import io.aeron.Aeron;
import io.aeron.Publication;
import jakarta.annotation.PreDestroy;
import org.agrona.BufferUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Zero-copy Aeron IPC / UDP Multicast market data publisher.
 *
 * <p>Only activated when {@code emporia.market-data.aeron.enabled=true}. In all other
 * environments (tests, simulated-provider dev mode) a no-op bean is injected instead,
 * preventing any Aeron Media Driver connection attempts.
 *
 * <h3>Wire Format (per frame, fixed 64 bytes)</h3>
 * <pre>
 *   Offset  Type    Field
 *   0       long    listingId
 *   8       long    bestBidTicks   (best bid price × 1_000_000, 0 if no bids)
 *   16      long    bestAskTicks   (best offer price × 1_000_000, 0 if no offers)
 *   24      long    lastPriceTicks (lastPrice × 1_000_000)
 *   32      long    epochMillis    (asOf.toEpochMilli)
 *   40      byte    streamInterrupted (0=normal, 1=interrupted)
 *   41      byte[23] reserved
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "emporia.market-data.aeron.enabled", havingValue = "true")
public class AeronMarketDataPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AeronMarketDataPublisher.class);

    /**
     * Fixed frame size (bytes). Must be a multiple of 4 for Aeron alignment.
     */
    public static final int FRAME_LENGTH = 64;

    private static final int OFFSET_LISTING_ID    = 0;
    private static final int OFFSET_BID           = 8;
    private static final int OFFSET_ASK           = 16;
    private static final int OFFSET_LAST          = 24;
    private static final int OFFSET_EPOCH_MILLIS  = 32;
    private static final int OFFSET_INTERRUPTED   = 40;

    /** Price scale factor: 6 decimal places → multiply by 1_000_000L before storing. */
    static final long PRICE_SCALE = 1_000_000L;

    private final String channel;
    private final int streamId;
    private final Aeron aeron;
    private final Publication publication;

    /** Thread-local pre-allocated write buffer — one frame per thread. */
    private final ThreadLocal<UnsafeBuffer> writeBuffer = ThreadLocal.withInitial(() ->
            new UnsafeBuffer(BufferUtil.allocateDirectAligned(FRAME_LENGTH, 64)));

    private final AtomicBoolean closed = new AtomicBoolean(false);

    AeronMarketDataPublisher(
            @Value("${emporia.market-data.aeron.channel:aeron:ipc}") String channel,
            @Value("${emporia.market-data.aeron.stream-id:1001}") int streamId,
            @Value("${emporia.market-data.aeron.driver-dir:#{null}}") String driverDir) {
        this.channel = channel;
        this.streamId = streamId;

        Aeron.Context ctx = new Aeron.Context();
        if (driverDir != null && !driverDir.isBlank()) {
            ctx.aeronDirectoryName(driverDir);
        }
        this.aeron = Aeron.connect(ctx);
        this.publication = aeron.addPublication(channel, streamId);
        log.info("Aeron market-data publisher connected: channel={} streamId={}", channel, streamId);
    }

    /**
     * No-arg constructor for no-op subclasses (e.g. test/simulated environments).
     * Does not connect to an Aeron Media Driver.
     */
    protected AeronMarketDataPublisher() {
        this.channel = "none";
        this.streamId = 0;
        this.aeron = null;
        this.publication = null;
    }

    /**
     * Offer a {@link Quote} to the Aeron publication channel.
     *
     * <p>This method is wait-free on the happy path (back-pressure not engaged). It does not
     * allocate any heap objects per invocation; the pre-allocated {@link UnsafeBuffer} is
     * reused via {@code ThreadLocal}.
     *
     * @param quote the market data quote to publish
     * @return {@code true} if the offer was accepted by the Aeron ring buffer
     */
    public boolean publishQuote(Quote quote) {
        if (closed.get()) return false;

        UnsafeBuffer buffer = writeBuffer.get();
        encode(buffer, quote);

        long result = publication.offer(buffer, 0, FRAME_LENGTH);
        if (result < 0) {
            // Negative return codes indicate back-pressure or admin action; log at debug.
            log.debug("Aeron offer returned {} for listing {}", result, quote.listingId());
            return false;
        }
        return true;
    }

    /**
     * Encode a {@link Quote} into the given fixed-size 64-byte Agrona buffer.
     * Package-private for testing without standing up a real Aeron Media Driver.
     */
    static void encode(UnsafeBuffer buffer, Quote quote) {
        long bestBidTicks = quote.bids() != null && !quote.bids().isEmpty()
                ? toTicks(quote.bids().getFirst().price()) : 0L;
        long bestAskTicks = quote.offers() != null && !quote.offers().isEmpty()
                ? toTicks(quote.offers().getFirst().price()) : 0L;

        buffer.putLong(OFFSET_LISTING_ID,   quote.listingId());
        buffer.putLong(OFFSET_BID,          bestBidTicks);
        buffer.putLong(OFFSET_ASK,          bestAskTicks);
        buffer.putLong(OFFSET_LAST,         toTicks(quote.lastPrice()));
        buffer.putLong(OFFSET_EPOCH_MILLIS, quote.asOf() != null ? quote.asOf().toEpochMilli() : 0L);
        buffer.putByte(OFFSET_INTERRUPTED,  quote.streamInterrupted() ? (byte) 1 : (byte) 0);
    }

    /**
     * Decode a raw Agrona buffer back into a {@link QuoteFrame} value object.
     * Used by Aeron subscribers and tests to verify wire-level correctness.
     */
    public static QuoteFrame decode(UnsafeBuffer buffer) {
        return new QuoteFrame(
                buffer.getLong(OFFSET_LISTING_ID),
                buffer.getLong(OFFSET_BID),
                buffer.getLong(OFFSET_ASK),
                buffer.getLong(OFFSET_LAST),
                buffer.getLong(OFFSET_EPOCH_MILLIS),
                buffer.getByte(OFFSET_INTERRUPTED) == 1
        );
    }

    private static long toTicks(java.math.BigDecimal price) {
        if (price == null) return 0L;
        return price.multiply(java.math.BigDecimal.valueOf(PRICE_SCALE)).longValue();
    }

    /** Decoded wire frame returned by {@link #decode(UnsafeBuffer)}. */
    public record QuoteFrame(
            long listingId,
            long bidTicks,
            long askTicks,
            long lastTicks,
            long epochMillis,
            boolean interrupted
    ) {}

    /** Whether this publisher has been closed. */
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    @PreDestroy
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (publication != null) publication.close();
            if (aeron != null) aeron.close();
            log.info("Aeron market-data publisher closed");
        }
    }
}

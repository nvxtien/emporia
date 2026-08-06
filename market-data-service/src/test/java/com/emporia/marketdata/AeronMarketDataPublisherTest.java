package com.emporia.marketdata;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.marketdata.MarketDataService.DepthLevel;
import com.emporia.marketdata.MarketDataService.Quote;
import org.agrona.BufferUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static com.emporia.marketdata.AeronMarketDataPublisher.FRAME_LENGTH;
import static com.emporia.marketdata.AeronMarketDataPublisher.PRICE_SCALE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AeronMarketDataPublisher} binary frame encoding/decoding.
 *
 * <p>Tests exercise the static {@code encode}/{@code decode} codecs using an in-process
 * {@link UnsafeBuffer} — no live Aeron Media Driver is required.
 */
class AeronMarketDataPublisherTest {

    @Test
    void encodeAndDecodeRoundTripPreservesAllFields() {
        BigDecimal bid   = new BigDecimal("149.50");
        BigDecimal ask   = new BigDecimal("150.00");
        BigDecimal last  = new BigDecimal("149.75");
        Instant    asOf  = Instant.ofEpochMilli(1_700_000_000_000L);

        Quote quote = new Quote(
                42L, "AAPL", "USD",
                last, BigDecimal.TEN,
                new BigDecimal("148.00"), new BigDecimal("1.75"), new BigDecimal("1.18"),
                new BigDecimal("1000000"),
                List.of(new DepthLevel(bid, BigDecimal.TEN, "XNAS")),
                List.of(new DepthLevel(ask, BigDecimal.TEN, "XNAS")),
                asOf, "SIMULATED"
        );

        UnsafeBuffer buffer = new UnsafeBuffer(BufferUtil.allocateDirectAligned(FRAME_LENGTH, 64));
        AeronMarketDataPublisher.encode(buffer, quote);
        AeronMarketDataPublisher.QuoteFrame frame = AeronMarketDataPublisher.decode(buffer);

        assertThat(frame.listingId()).isEqualTo(42L);
        assertThat(frame.bidTicks()).isEqualTo(bid.multiply(BigDecimal.valueOf(PRICE_SCALE)).longValue());
        assertThat(frame.askTicks()).isEqualTo(ask.multiply(BigDecimal.valueOf(PRICE_SCALE)).longValue());
        assertThat(frame.lastTicks()).isEqualTo(last.multiply(BigDecimal.valueOf(PRICE_SCALE)).longValue());
        assertThat(frame.epochMillis()).isEqualTo(asOf.toEpochMilli());
        assertThat(frame.interrupted()).isFalse();
    }

    @Test
    void encodeInterruptedQuoteSetsInterruptedFlag() {
        Instant now = Instant.now();
        Quote base = new Quote(
                7L, "MSFT", "USD",
                new BigDecimal("300.00"), BigDecimal.ONE,
                new BigDecimal("298.00"), new BigDecimal("2.00"), new BigDecimal("0.67"),
                new BigDecimal("500000"),
                List.of(), List.of(), now, "SIMULATED"
        );
        Quote interrupted = base.interrupted("Provider timeout", now);

        UnsafeBuffer buffer = new UnsafeBuffer(BufferUtil.allocateDirectAligned(FRAME_LENGTH, 64));
        AeronMarketDataPublisher.encode(buffer, interrupted);
        AeronMarketDataPublisher.QuoteFrame frame = AeronMarketDataPublisher.decode(buffer);

        assertThat(frame.listingId()).isEqualTo(7L);
        assertThat(frame.interrupted()).isTrue();
    }

    @Test
    void encodeQuoteWithNoBidsOrOffersYieldsZeroTicks() {
        Quote quote = new Quote(
                99L, "TSLA", "USD",
                new BigDecimal("250.00"), BigDecimal.ONE,
                new BigDecimal("248.00"), new BigDecimal("2.00"), new BigDecimal("0.81"),
                new BigDecimal("200000"),
                List.of(), List.of(),   // no bids, no offers
                Instant.EPOCH, "SIMULATED"
        );

        UnsafeBuffer buffer = new UnsafeBuffer(BufferUtil.allocateDirectAligned(FRAME_LENGTH, 64));
        AeronMarketDataPublisher.encode(buffer, quote);
        AeronMarketDataPublisher.QuoteFrame frame = AeronMarketDataPublisher.decode(buffer);

        assertThat(frame.bidTicks()).isEqualTo(0L);
        assertThat(frame.askTicks()).isEqualTo(0L);
        assertThat(frame.lastTicks())
                .isEqualTo(new BigDecimal("250.00").multiply(BigDecimal.valueOf(PRICE_SCALE)).longValue());
    }

    @Test
    void frameLengthIs64Bytes() {
        assertThat(FRAME_LENGTH).isEqualTo(64);
    }
}

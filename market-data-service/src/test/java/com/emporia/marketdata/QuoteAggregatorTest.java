package com.emporia.marketdata;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.marketdata.MarketDataService.DepthLevel;
import com.emporia.marketdata.MarketDataService.Quote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteAggregatorTest {
    private final QuoteAggregator aggregator = new QuoteAggregator();

    @Test
    void mergesVenueBooksAndPreservesVenueListingAttribution() {
        ListingSnapshot composite = listing(1001, "XOSR");
        Quote nasdaq = quote(1, "XNAS", "199.90", "200.20", "10", "2026-07-23T10:00:00Z");
        Quote nyse = quote(2, "XNYS", "200.00", "200.10", "20", "2026-07-23T10:00:01Z");

        Quote result = aggregator.aggregate(composite, List.of(nasdaq, nyse));

        assertThat(result.source()).isEqualTo("AGGREGATED");
        assertThat(result.lastPrice()).isEqualByComparingTo(nyse.lastPrice());
        assertThat(result.tradedVolume()).isEqualByComparingTo("30");
        assertThat(result.bids()).extracting(DepthLevel::price)
                .containsExactly(new BigDecimal("200.00"), new BigDecimal("199.90"));
        assertThat(result.bids()).extracting(DepthLevel::listingId).containsExactly(2L, 1L);
        assertThat(result.offers()).extracting(DepthLevel::price)
                .containsExactly(new BigDecimal("200.10"), new BigDecimal("200.20"));
    }

    @Test
    void exposesAnInterruptedEmptyBookWhenNoVenueIsAvailable() {
        Quote result = aggregator.aggregate(listing(1001, "XOSR"), List.of());

        assertThat(result.streamInterrupted()).isTrue();
        assertThat(result.streamStatusMessage()).contains("No venue quote");
        assertThat(result.bids()).isEmpty();
        assertThat(result.offers()).isEmpty();
    }

    private static ListingSnapshot listing(long id, String mic) {
        return new ListingSnapshot(id, 1, "AAPL", "Apple Inc.", "AAPL", mic, mic, "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("200"), new BigDecimal("198"));
    }

    private static Quote quote(long listingId, String mic, String bid, String offer, String volume, String timestamp) {
        BigDecimal last = new BigDecimal(bid);
        return new Quote(listingId, "AAPL", "USD", last, BigDecimal.ONE, new BigDecimal("198"),
                last.subtract(new BigDecimal("198")), BigDecimal.ONE, new BigDecimal(volume),
                List.of(new DepthLevel(new BigDecimal(bid), new BigDecimal("100"), mic)),
                List.of(new DepthLevel(new BigDecimal(offer), new BigDecimal("200"), mic)),
                Instant.parse(timestamp), "TEST");
    }
}

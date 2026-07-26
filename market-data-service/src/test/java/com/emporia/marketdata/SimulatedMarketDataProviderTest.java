package com.emporia.marketdata;

import com.emporia.events.TradingEvents.ListingSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedMarketDataProviderTest {

    @Test
    void producesFiveLevelsOnEachSide() {
        SimulatedMarketDataProvider provider = new SimulatedMarketDataProvider();
        Instant timestamp = Instant.parse("2026-07-23T12:00:00Z");

        MarketDataService.Quote quote = provider.quote(listing(), timestamp);

        assertThat(quote.source()).isEqualTo("SIMULATED");
        assertThat(quote.bids()).hasSize(5);
        assertThat(quote.offers()).hasSize(5);
        assertThat(quote.asOf()).isEqualTo(timestamp);
    }

    @Test
    void isDeterministicForAListingAndTimestamp() {
        SimulatedMarketDataProvider provider = new SimulatedMarketDataProvider();
        Instant timestamp = Instant.parse("2026-07-23T12:00:00Z");

        MarketDataService.Quote first = provider.quote(listing(), timestamp);
        MarketDataService.Quote second = provider.quote(listing(), timestamp);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void createsOrderedPositiveDepthAtTickSizeIntervals() {
        SimulatedMarketDataProvider provider = new SimulatedMarketDataProvider();

        MarketDataService.Quote quote = provider.quote(listing(), Instant.parse("2026-07-23T12:00:00Z"));

        assertThat(quote.bids()).extracting(MarketDataService.DepthLevel::price).isSortedAccordingTo(
                (left, right) -> right.compareTo(left));
        assertThat(quote.offers()).extracting(MarketDataService.DepthLevel::price).isSorted();
        assertThat(quote.bids()).allSatisfy(level -> {
            assertThat(level.price()).isLessThan(quote.lastPrice());
            assertThat(level.size()).isPositive();
            assertThat(level.exchangeMic()).isEqualTo("XNAS");
        });
        assertThat(quote.offers()).allSatisfy(level -> {
            assertThat(level.price()).isGreaterThan(quote.lastPrice());
            assertThat(level.size()).isPositive();
            assertThat(level.exchangeMic()).isEqualTo("XNAS");
        });
        assertThat(quote.bids().get(0).price().subtract(quote.bids().get(1).price()))
                .isEqualByComparingTo("0.01");
        assertThat(quote.offers().get(1).price().subtract(quote.offers().get(0).price()))
                .isEqualByComparingTo("0.01");
    }

    @Test
    void preservesBatchListingOrder() {
        SimulatedMarketDataProvider provider = new SimulatedMarketDataProvider();
        ListingSnapshot microsoft = new ListingSnapshot(
                2, 1, "MSFT", "Microsoft Corp.", "MSFT", "XNAS", "Nasdaq", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("390.00"), new BigDecimal("388.00")
        );

        List<MarketDataService.Quote> quotes = provider.quotes(
                List.of(microsoft, listing()), Instant.parse("2026-07-23T12:00:00Z"));

        assertThat(quotes).extracting(MarketDataService.Quote::listingId).containsExactly(2L, 1L);
    }

    private static ListingSnapshot listing() {
        return new ListingSnapshot(1, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("200.00"), new BigDecimal("198.00"));
    }
}

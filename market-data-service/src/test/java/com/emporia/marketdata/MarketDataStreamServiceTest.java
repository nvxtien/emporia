package com.emporia.marketdata;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.marketdata.MarketDataService.DepthLevel;
import com.emporia.marketdata.MarketDataService.Quote;
import com.emporia.marketdata.MarketDataService.ResolvedListings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketDataStreamServiceTest {
    private final MarketDataService marketData = mock(MarketDataService.class);
    private final AeronMarketDataPublisher aeronPublisher = mock(AeronMarketDataPublisher.class);
    private MarketDataStreamService streamService;

    @BeforeEach
    void setUp() {
        streamService = new MarketDataStreamService(
                marketData,
                aeronPublisher,
                new SimpleMeterRegistry(),
                Duration.ofMillis(50),
                Duration.ofSeconds(1)
        );
    }

    @Test
    void subscribeAndInitialSnapshot() throws Exception {
        ListingSnapshot listing = sampleListing(1L, "AAPL");
        ResolvedListings resolved = new ResolvedListings(List.of(listing), Map.of(1L, List.of(listing)));
        when(marketData.resolveIds(List.of(1L), "Bearer token")).thenReturn(resolved);

        Quote quote = sampleQuote(1L, "AAPL", new BigDecimal("150.00"));
        when(marketData.snapshot(eq(resolved), any(Instant.class))).thenReturn(List.of(quote));

        List<Quote> received = new ArrayList<>();
        ConflatedQuoteSubscription subscription = streamService.subscribe(List.of(1L), "Bearer token", received::add);

        Thread.sleep(100);

        assertThat(subscription).isNotNull();
        assertThat(streamService.activeSubscribers()).isEqualTo(1);
        assertThat(received).hasSize(1);
        assertThat(received.getFirst().symbol()).isEqualTo("AAPL");

        subscription.close();
        assertThat(streamService.activeSubscribers()).isEqualTo(0);
    }

    @Test
    void addListingMergesAndOffersSnapshot() throws Exception {
        ListingSnapshot l1 = sampleListing(1L, "AAPL");
        ListingSnapshot l2 = sampleListing(2L, "MSFT");
        ResolvedListings initial = new ResolvedListings(List.of(l1), Map.of(1L, List.of(l1)));
        ResolvedListings addition = new ResolvedListings(List.of(l2), Map.of(2L, List.of(l2)));
        ResolvedListings merged = new ResolvedListings(List.of(l1, l2), Map.of(1L, List.of(l1), 2L, List.of(l2)));

        when(marketData.resolveIds(List.of(1L), "token")).thenReturn(initial);
        when(marketData.resolveIds(List.of(2L), "token")).thenReturn(addition);
        when(marketData.merge(any())).thenReturn(merged);

        Quote q1 = sampleQuote(1L, "AAPL", new BigDecimal("150.00"));
        Quote q2 = sampleQuote(2L, "MSFT", new BigDecimal("300.00"));
        when(marketData.snapshot(eq(initial), any())).thenReturn(List.of(q1));
        when(marketData.snapshot(eq(addition), any())).thenReturn(List.of(q2));

        List<Quote> received = new ArrayList<>();
        ConflatedQuoteSubscription sub = streamService.subscribe(initial, received::add);
        Thread.sleep(100);
        assertThat(received).hasSize(1);

        streamService.addListing(sub, 2L, "token");
        Thread.sleep(100);
        assertThat(received).hasSize(2);
        assertThat(received.get(1).symbol()).isEqualTo("MSFT");

        streamService.shutdown();
        assertThat(streamService.activeSubscribers()).isEqualTo(0);
    }

    private static ListingSnapshot sampleListing(long id, String symbol) {
        return new ListingSnapshot(id, 1, symbol, symbol + " Inc", symbol, "XNAS", "Nasdaq", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("150.00"), new BigDecimal("150.00"));
    }

    private static Quote sampleQuote(long listingId, String symbol, BigDecimal price) {
        return new Quote(listingId, symbol, "USD", price, BigDecimal.TEN, price, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.TEN, List.of(new DepthLevel(price, BigDecimal.TEN, "XNAS")), List.of(), Instant.now(), "SIMULATED");
    }
}

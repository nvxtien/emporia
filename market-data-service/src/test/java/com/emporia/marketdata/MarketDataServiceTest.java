package com.emporia.marketdata;

import com.emporia.events.TradingEvents.ListingSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDataServiceTest {
    private final StaticDataClient staticData = mock(StaticDataClient.class);
    private final MarketDataProvider provider = mock(MarketDataProvider.class);
    private final MarketDataService service = new MarketDataService(staticData, provider, new QuoteAggregator());

    @Test
    void loadsAListingAndDelegatesSingleQuoteGeneration() {
        ListingSnapshot listing = listing(1, "AAPL");
        MarketDataService.Quote expected = quote(listing, Instant.parse("2026-07-23T12:00:00Z"));
        when(staticData.get(1, "Bearer token")).thenReturn(listing);
        when(provider.quote(eq(listing), any(Instant.class))).thenReturn(expected);

        MarketDataService.Quote actual = service.getQuote(1, "Bearer token");

        assertThat(actual).isSameAs(expected);
        verify(staticData).get(1, "Bearer token");
        verify(provider).quote(eq(listing), any(Instant.class));
    }

    @Test
    void restoresRequestedListingOrderBeforeCallingTheProvider() {
        ListingSnapshot apple = listing(1, "AAPL");
        ListingSnapshot microsoft = listing(2, "MSFT");
        List<Long> requestedIds = List.of(2L, 1L);
        when(staticData.batch(requestedIds, "Bearer token")).thenReturn(List.of(apple, microsoft));
        when(provider.quotes(any(), any(Instant.class))).thenReturn(List.of());

        service.getQuotes(requestedIds, "Bearer token");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ListingSnapshot>> listings = ArgumentCaptor.forClass(List.class);
        verify(provider).quotes(listings.capture(), any(Instant.class));
        assertThat(listings.getValue()).extracting(ListingSnapshot::id).containsExactly(2L, 1L);
        verify(staticData).batch(requestedIds, "Bearer token");
    }

    @Test
    void resolvesACompositeListingAcrossAllPhysicalVenues() {
        ListingSnapshot composite = new ListingSnapshot(1001, 1, "AAPL", "Apple Inc.", "AAPL", "XOSR",
                "Smart Order Router", "US", "USD", new BigDecimal("0.01"), BigDecimal.ONE,
                new BigDecimal("200.00"), new BigDecimal("198.00"));
        ListingSnapshot nasdaq = listing(1, "AAPL");
        when(staticData.get(1001, "Bearer token")).thenReturn(composite);
        when(staticData.bySymbols(List.of("AAPL"), "Bearer token")).thenReturn(List.of(composite, nasdaq));
        when(provider.quotes(any(), any(Instant.class)))
                .thenReturn(List.of(quote(nasdaq, Instant.parse("2026-07-23T12:00:00Z"))));

        MarketDataService.Quote result = service.getQuote(1001, "Bearer token");

        assertThat(result.listingId()).isEqualTo(1001);
        assertThat(result.source()).isEqualTo("AGGREGATED");
        verify(provider).quotes(eq(List.of(nasdaq)), any(Instant.class));
    }

    private static ListingSnapshot listing(long id, String symbol) {
        return new ListingSnapshot(id, 1, symbol, symbol + " Inc.", symbol, "XNAS", "Nasdaq", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("200.00"), new BigDecimal("198.00"));
    }

    private static MarketDataService.Quote quote(ListingSnapshot listing, Instant timestamp) {
        return new MarketDataService.Quote(
                listing.id(), listing.symbol(), listing.currency(), listing.referencePrice(), BigDecimal.ONE,
                listing.previousClose(), BigDecimal.TWO, BigDecimal.ONE, BigDecimal.TEN, List.of(), List.of(),
                timestamp, "TEST"
        );
    }
}

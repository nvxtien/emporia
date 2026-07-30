package com.emporia.userpreferences;

import com.emporia.events.TradingEvents.ListingSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchlistServiceTest {
    private final WatchlistEntryRepository repository = mock(WatchlistEntryRepository.class);
    private final StaticDataClient staticData = mock(StaticDataClient.class);
    private WatchlistService service;

    @BeforeEach
    void setUp() {
        service = new WatchlistService(repository, staticData);
    }

    @Test
    void getInitializesDefaultsWhenUserHasNoWatchlist() {
        when(repository.countByUserSubject("user-1")).thenReturn(0L);
        ListingSnapshot aaplXnas = listing(1L, "AAPL", "XNAS");
        ListingSnapshot aaplXosr = listing(2L, "AAPL", "XOSR");
        when(staticData.bySymbols(any(), anyString())).thenReturn(List.of(aaplXnas, aaplXosr));

        WatchlistEntry entry = new WatchlistEntry("user-1", 2L, 0);
        when(repository.findByUserSubjectOrderByDisplayOrderAscAddedAtAsc("user-1")).thenReturn(List.of(entry));
        when(staticData.batch(List.of(2L), "Bearer token")).thenReturn(List.of(aaplXosr));

        List<WatchlistService.WatchlistItem> result = service.get("user-1", "Bearer token");
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().listing().symbol()).isEqualTo("AAPL");
        verify(repository).insertIfAbsent(any(), anyString(), anyLong(), anyInt(), any());
    }

    @Test
    void getPrefersCurrentXosrAndComparesListingIdsAsTieBreaker() {
        when(repository.countByUserSubject("user-2")).thenReturn(0L);
        ListingSnapshot aaplXosr = listing(10L, "AAPL", "XOSR");
        ListingSnapshot aaplXnas = listing(5L, "AAPL", "XNAS");
        ListingSnapshot msftXnas1 = listing(20L, "MSFT", "XNAS");
        ListingSnapshot msftXnas2 = listing(30L, "MSFT", "XNAS");
        when(staticData.bySymbols(any(), anyString())).thenReturn(List.of(aaplXosr, aaplXnas, msftXnas1, msftXnas2));

        WatchlistEntry entry1 = new WatchlistEntry("user-2", 10L, 0);
        WatchlistEntry entry2 = new WatchlistEntry("user-2", 20L, 1);
        when(repository.findByUserSubjectOrderByDisplayOrderAscAddedAtAsc("user-2")).thenReturn(List.of(entry1, entry2));
        when(staticData.batch(List.of(10L, 20L), "Bearer token")).thenReturn(List.of(aaplXosr, msftXnas1));

        List<WatchlistService.WatchlistItem> result = service.get("user-2", "Bearer token");
        assertThat(result).hasSize(2);
    }

    @Test
    void addWatchlistItemSuccess() {
        when(repository.countByUserSubject("user-1")).thenReturn(1L);
        ListingSnapshot snapshot = listing(10L, "MSFT", "XNAS");
        when(staticData.get(10L, "Bearer token")).thenReturn(snapshot);

        WatchlistEntry entry = new WatchlistEntry("user-1", 10L, 1);
        when(repository.findByUserSubjectAndListingId("user-1", 10L)).thenReturn(Optional.of(entry));

        WatchlistService.WatchlistItem item = service.add("user-1", 10L, "Bearer token");
        assertThat(item).isNotNull();
        assertThat(item.listing().symbol()).isEqualTo("MSFT");
    }

    @Test
    void addWatchlistItemThrowsWhenNotStored() {
        when(repository.countByUserSubject("user-1")).thenReturn(1L);
        ListingSnapshot snapshot = listing(10L, "MSFT", "XNAS");
        when(staticData.get(10L, "Bearer token")).thenReturn(snapshot);
        when(repository.findByUserSubjectAndListingId("user-1", 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.add("user-1", 10L, "Bearer token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Watchlist entry was not stored");
    }

    @Test
    void removeWatchlistItemSuccess() {
        service.remove("user-1", 10L);
        verify(repository).deleteByUserSubjectAndListingId("user-1", 10L);
    }

    private static ListingSnapshot listing(long id, String symbol, String mic) {
        return new ListingSnapshot(id, 1, symbol, symbol + " Inc", symbol, mic, "Exchange", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"));
    }
}

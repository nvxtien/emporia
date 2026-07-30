package com.emporia.userpreferences;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.userpreferences.WatchlistService.WatchlistItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchlistControllerTest {
    private final WatchlistService watchlist = mock(WatchlistService.class);
    private final Jwt jwt = mock(Jwt.class);
    private WatchlistController controller;

    @BeforeEach
    void setUp() {
        controller = new WatchlistController(watchlist);
        when(jwt.getSubject()).thenReturn("user-sub-123");
    }

    @Test
    void getReturnsWatchlistItems() {
        WatchlistItem item = new WatchlistItem("id-1", 0, Instant.now(), listing(10L, "AAPL", "XNAS"));
        when(watchlist.get("user-sub-123", "Bearer auth")).thenReturn(List.of(item));

        List<WatchlistItem> items = controller.get(jwt, "Bearer auth");
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().listing().symbol()).isEqualTo("AAPL");
    }

    @Test
    void addDelegatesToService() {
        WatchlistItem item = new WatchlistItem("id-1", 0, Instant.now(), listing(10L, "AAPL", "XNAS"));
        when(watchlist.add("user-sub-123", 10L, "Bearer auth")).thenReturn(item);

        WatchlistItem result = controller.add(jwt, 10L, "Bearer auth");
        assertThat(result).isEqualTo(item);
    }

    @Test
    void removeDelegatesToService() {
        controller.remove(jwt, 10L);
        verify(watchlist).remove("user-sub-123", 10L);
    }

    private static ListingSnapshot listing(long id, String symbol, String mic) {
        return new ListingSnapshot(id, 1, symbol, symbol + " Inc", symbol, mic, "Exchange", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"));
    }
}

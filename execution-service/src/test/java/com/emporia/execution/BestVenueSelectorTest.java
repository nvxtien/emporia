package com.emporia.execution;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.execution.TradingDataClient.DepthLevel;
import com.emporia.execution.TradingDataClient.MarketQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BestVenueSelectorTest {
    private final BestVenueSelector selector = new BestVenueSelector();

    @Test
    void selectsBestOfferForBuyOrder() {
        ListingSnapshot listing1 = listing(1L, "AAPL", "XNAS");
        ListingSnapshot listing2 = listing(2L, "AAPL", "XNYS");

        MarketQuote quote = new MarketQuote(1L, new BigDecimal("150.00"),
                List.of(new DepthLevel(new BigDecimal("150.00"), new BigDecimal("100"), "XNAS", "e1", 1L)),
                List.of(new DepthLevel(new BigDecimal("150.50"), new BigDecimal("50"), "XNAS", "e2", 1L),
                        new DepthLevel(new BigDecimal("150.25"), new BigDecimal("100"), "XNYS", "e3", 2L)));

        BestVenueSelector.Selection selection = selector.select(OrderSide.BUY, new BigDecimal("151.00"), List.of(listing1, listing2), List.of(quote));
        assertThat(selection.listing().exchangeMic()).isEqualTo("XNYS");
        assertThat(selection.price()).isEqualByComparingTo("150.25");
    }

    @Test
    void selectsBestBidForSellOrder() {
        ListingSnapshot listing1 = listing(1L, "AAPL", "XNAS");

        MarketQuote quote = new MarketQuote(1L, new BigDecimal("150.00"),
                List.of(new DepthLevel(new BigDecimal("150.00"), new BigDecimal("100"), "XNAS", "e1", 1L),
                        new DepthLevel(new BigDecimal("150.50"), new BigDecimal("50"), "XNAS", "e2", 1L)),
                List.of());

        BestVenueSelector.Selection selection = selector.select(OrderSide.SELL, new BigDecimal("149.00"), List.of(listing1), List.of(quote));
        assertThat(selection.price()).isEqualByComparingTo("150.50");
    }

    @Test
    void throwsWhenNoLiquidity() {
        ListingSnapshot listing1 = listing(1L, "AAPL", "XNAS");
        MarketQuote quote = new MarketQuote(1L, new BigDecimal("150.00"), List.of(), List.of());

        assertThatThrownBy(() -> selector.select(OrderSide.BUY, null, List.of(listing1), List.of(quote)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No executable venue has market liquidity");
    }

    @Test
    void plansRouteSlices() {
        ListingSnapshot listing1 = listing(1L, "AAPL", "XNAS");

        MarketQuote quote = new MarketQuote(1L, new BigDecimal("150.00"),
                List.of(),
                List.of(new DepthLevel(new BigDecimal("150.00"), new BigDecimal("100"), "XNAS", "e1", 1L)));

        List<BestVenueSelector.RouteSlice> slices = selector.plan(OrderSide.BUY, new BigDecimal("151.00"),
                List.of(listing1), List.of(quote), new BigDecimal("50"), BigDecimal.ONE);

        assertThat(slices).hasSize(1);
        assertThat(slices.getFirst().quantity()).isEqualByComparingTo("50");
    }

    @Test
    void rejectsInvalidRequestedQuantity() {
        ListingSnapshot listing1 = listing(1L, "AAPL", "XNAS");
        assertThatThrownBy(() -> selector.plan(OrderSide.BUY, null, List.of(listing1), List.of(), new BigDecimal("10.5"), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filtersOutXosrExchangeListings() {
        ListingSnapshot xosr = listing(1L, "AAPL", "XOSR");
        MarketQuote quote = new MarketQuote(1L, new BigDecimal("100"),
                List.of(),
                List.of(new DepthLevel(new BigDecimal("100"), new BigDecimal("50"), "XOSR", "e1", 1L)));

        // XOSR listing should be excluded, so no candidates → exception
        assertThatThrownBy(() -> selector.select(OrderSide.BUY, null, List.of(xosr), List.of(quote)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No executable venue has market liquidity");
    }

    @Test
    void filtersOutDepthLevelsThatViolateSellLimitPrice() {
        ListingSnapshot listing = listing(1L, "AAPL", "XNAS");
        // Sell order with limit price 100 — bids below 100 must be excluded
        MarketQuote quote = new MarketQuote(1L, new BigDecimal("100"),
                List.of(new DepthLevel(new BigDecimal("99"), new BigDecimal("50"), "XNAS", "e1", 1L)),
                List.of());

        assertThatThrownBy(() -> selector.select(OrderSide.SELL, new BigDecimal("100.00"), List.of(listing), List.of(quote)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void planThrowsWhenDepthIsEmptyAfterFiltering() {
        ListingSnapshot listing = listing(1L, "AAPL", "XNAS");
        // Quote with no offers → no candidates for a BUY
        MarketQuote quote = new MarketQuote(1L, new BigDecimal("100"), List.of(), List.of());

        assertThatThrownBy(() -> selector.plan(OrderSide.BUY, null, List.of(listing), List.of(quote),
                new BigDecimal("10"), BigDecimal.ONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No executable venue has market liquidity");
    }

    @Test
    void planCapsSliceQuantityToAvailableDepth() {
        ListingSnapshot listing = listing(1L, "AAPL", "XNAS");
        // Depth has only 30 available, request 50
        MarketQuote quote = new MarketQuote(1L, new BigDecimal("100"),
                List.of(),
                List.of(new DepthLevel(new BigDecimal("100"), new BigDecimal("30"), "XNAS", "e1", 1L)));

        List<BestVenueSelector.RouteSlice> slices = selector.plan(OrderSide.BUY, null,
                List.of(listing), List.of(quote), new BigDecimal("50"), BigDecimal.ONE);

        // Only 30 available, so result is capped to 30
        assertThat(slices).hasSize(1);
        assertThat(slices.getFirst().quantity()).isEqualByComparingTo("30");
    }

    @Test
    void planRejectsNullSizeIncrement() {
        ListingSnapshot listing = listing(1L, "AAPL", "XNAS");
        assertThatThrownBy(() -> selector.plan(OrderSide.BUY, null, List.of(listing), List.of(),
                new BigDecimal("10"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ListingSnapshot listing(long id, String symbol, String mic) {
        return new ListingSnapshot(id, 1, symbol, symbol + " Inc", symbol, mic, "Exchange", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"));
    }
}

package com.emporia.execution;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.execution.TradingDataClient.DepthLevel;
import com.emporia.execution.TradingDataClient.MarketQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BestVenueSelectorTest {
    private final BestVenueSelector selector = new BestVenueSelector();

    @Test
    void buysAtTheLowestOfferAndSellsAtTheHighestBid() {
        ListingSnapshot nasdaq = listing(1, "XNAS");
        ListingSnapshot iex = listing(2, "IEXG");
        MarketQuote nasdaqQuote = quote(1, "100.10", "100.20");
        MarketQuote iexQuote = quote(2, "100.12", "100.18");

        assertThat(selector.select(OrderSide.BUY, null, List.of(nasdaq, iex), List.of(nasdaqQuote, iexQuote))
                .listing().exchangeMic()).isEqualTo("IEXG");
        assertThat(selector.select(OrderSide.SELL, null, List.of(nasdaq, iex), List.of(nasdaqQuote, iexQuote))
                .listing().exchangeMic()).isEqualTo("IEXG");
    }

    @Test
    void observesTheParentLimit() {
        ListingSnapshot nasdaq = listing(1, "XNAS");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> selector.select(
                        OrderSide.BUY, new BigDecimal("100.00"), List.of(nasdaq), List.of(quote(1, "99.90", "100.20"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void walksExecutableDepthAcrossVenuesWithoutInventingReferencePriceLiquidity() {
        ListingSnapshot nasdaq = listing(1, "XNAS");
        ListingSnapshot iex = listing(2, "IEXG");
        var plan = selector.plan(OrderSide.BUY, new BigDecimal("100.20"),
                List.of(nasdaq, iex),
                List.of(
                        new MarketQuote(1, null, List.of(), List.of(
                                new DepthLevel(new BigDecimal("100.10"), new BigDecimal("3"), "XNAS", "1", 1))),
                        new MarketQuote(2, null, List.of(), List.of(
                                new DepthLevel(new BigDecimal("100.15"), new BigDecimal("7"), "IEXG", "2", 2)))
                ),
                new BigDecimal("10"), BigDecimal.ONE);

        assertThat(plan).extracting(BestVenueSelector.RouteSlice::quantity)
                .containsExactly(new BigDecimal("3"), new BigDecimal("7"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> selector.select(
                        OrderSide.BUY, null, List.of(nasdaq),
                        List.of(new MarketQuote(1, null, List.of(), List.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("liquidity");
    }

    private ListingSnapshot listing(long id, String mic) {
        return new ListingSnapshot(id, 1, "AAPL", "Apple", "AAPL", mic, mic, "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("99"));
    }

    private MarketQuote quote(long id, String bid, String offer) {
        return new MarketQuote(id, new BigDecimal("100"),
                List.of(new DepthLevel(new BigDecimal(bid), BigDecimal.ONE, "X", "", id)),
                List.of(new DepthLevel(new BigDecimal(offer), BigDecimal.ONE, "X", "", id)));
    }
}

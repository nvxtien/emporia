package com.emporia.strategy.sor;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BestVenueSelectorTest {

    private final BestVenueSelector selector = new BestVenueSelector();

    private final ListingSnapshot listing1 = new ListingSnapshot(1L, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq", "US", "USD", new BigDecimal("0.01"), new BigDecimal("1.00"), new BigDecimal("150.00"), new BigDecimal("150.00"));
    private final ListingSnapshot listing2 = new ListingSnapshot(2L, 1, "AAPL", "Apple Inc.", "AAPL", "XNYS", "NYSE", "US", "USD", new BigDecimal("0.01"), new BigDecimal("1.00"), new BigDecimal("150.00"), new BigDecimal("150.00"));

    @Test
    void selectsBestAskPriceForBuyOrder() {
        BestVenueSelector.MarketQuoteView quote1 = new BestVenueSelector.MarketQuoteView("XNAS", new BigDecimal("150.00"), new BigDecimal("100"), new BigDecimal("150.50"), new BigDecimal("100"));
        BestVenueSelector.MarketQuoteView quote2 = new BestVenueSelector.MarketQuoteView("XNYS", new BigDecimal("150.00"), new BigDecimal("100"), new BigDecimal("150.25"), new BigDecimal("100"));

        BestVenueSelector.Selection selection = selector.select(
                OrderSide.BUY, new BigDecimal("151.00"), List.of(listing1, listing2), List.of(quote1, quote2));

        assertThat(selection.listing().exchangeMic()).isEqualTo("XNYS");
        assertThat(selection.price()).isEqualTo(new BigDecimal("150.25"));
    }

    @Test
    void selectsBestBidPriceForSellOrder() {
        BestVenueSelector.MarketQuoteView quote1 = new BestVenueSelector.MarketQuoteView("XNAS", new BigDecimal("150.75"), new BigDecimal("100"), new BigDecimal("151.00"), new BigDecimal("100"));
        BestVenueSelector.MarketQuoteView quote2 = new BestVenueSelector.MarketQuoteView("XNYS", new BigDecimal("150.50"), new BigDecimal("100"), new BigDecimal("151.00"), new BigDecimal("100"));

        BestVenueSelector.Selection selection = selector.select(
                OrderSide.SELL, new BigDecimal("149.00"), List.of(listing1, listing2), List.of(quote1, quote2));

        assertThat(selection.listing().exchangeMic()).isEqualTo("XNAS");
        assertThat(selection.price()).isEqualTo(new BigDecimal("150.75"));
    }

    @Test
    void plansMultiVenueSlicesCorrectly() {
        BestVenueSelector.MarketQuoteView quote1 = new BestVenueSelector.MarketQuoteView("XNAS", new BigDecimal("150.00"), new BigDecimal("100"), new BigDecimal("150.10"), new BigDecimal("50"));
        BestVenueSelector.MarketQuoteView quote2 = new BestVenueSelector.MarketQuoteView("XNYS", new BigDecimal("150.00"), new BigDecimal("100"), new BigDecimal("150.20"), new BigDecimal("100"));

        List<BestVenueSelector.RouteSlice> slices = selector.plan(
                OrderSide.BUY, new BigDecimal("151.00"), List.of(listing1, listing2), List.of(quote1, quote2),
                new BigDecimal("120"), new BigDecimal("1.00"));

        assertThat(slices).hasSize(2);
        assertThat(slices.get(0).listing().exchangeMic()).isEqualTo("XNAS");
        assertThat(slices.get(0).quantity()).isEqualByComparingTo(new BigDecimal("50"));
        assertThat(slices.get(1).listing().exchangeMic()).isEqualTo("XNYS");
        assertThat(slices.get(1).quantity()).isEqualByComparingTo(new BigDecimal("70"));
    }
}

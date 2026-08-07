package com.emporia.execution;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.execution.TradingDataClient.DepthLevel;
import com.emporia.execution.TradingDataClient.MarketQuote;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BestVenueSelectorPropertyTest {

    private final BestVenueSelector selector = new BestVenueSelector();

    @Property(tries = 250)
    void planRoutesWithinLimitPriceAndPreservesIncrement(
            @ForAll OrderSide side,
            @ForAll @LongRange(min = 10, max = 500) long qtyLots,
            @ForAll @LongRange(min = 100, max = 200) long limitTicks
    ) {
        BigDecimal sizeIncrement = new BigDecimal("0.01");
        BigDecimal limitPrice = BigDecimal.valueOf(limitTicks, 2);
        BigDecimal requestedQty = BigDecimal.valueOf(qtyLots, 2);

        ListingSnapshot venueA = new ListingSnapshot(
                1L, 1, "AAPL", "Apple Inc", "AAPL", "XNAS", "Nasdaq",
                "US", "USD", new BigDecimal("0.01"), sizeIncrement,
                new BigDecimal("150"), new BigDecimal("149")
        );
        ListingSnapshot venueB = new ListingSnapshot(
                2L, 1, "AAPL", "Apple Inc", "AAPL", "XNYS", "NYSE",
                "US", "USD", new BigDecimal("0.01"), sizeIncrement,
                new BigDecimal("150"), new BigDecimal("149")
        );

        DepthLevel offerA = new DepthLevel(new BigDecimal("140.00"), new BigDecimal("100.00"), "XNAS", "Nasdaq", 1L);
        DepthLevel offerB = new DepthLevel(new BigDecimal("145.00"), new BigDecimal("100.00"), "XNYS", "NYSE", 2L);

        MarketQuote quote = new MarketQuote(
                1L, new BigDecimal("142.50"), List.of(offerA, offerB), List.of(offerA, offerB)
        );

        if (side == OrderSide.BUY && limitPrice.compareTo(new BigDecimal("140.00")) >= 0) {
            List<BestVenueSelector.RouteSlice> slices = selector.plan(
                    side, limitPrice, List.of(venueA, venueB), List.of(quote), requestedQty, sizeIncrement
            );

            assertThat(slices).isNotEmpty();
            BigDecimal totalRouted = slices.stream()
                    .map(BestVenueSelector.RouteSlice::quantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(totalRouted).isLessThanOrEqualTo(requestedQty);
            for (BestVenueSelector.RouteSlice slice : slices) {
                assertThat(slice.quantity().remainder(sizeIncrement)).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(slice.price()).isLessThanOrEqualTo(limitPrice);
            }
        }
    }
}

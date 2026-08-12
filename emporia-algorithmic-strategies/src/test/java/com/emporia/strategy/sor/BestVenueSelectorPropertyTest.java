package com.emporia.strategy.sor;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BestVenueSelectorPropertyTest {

    private final BestVenueSelector selector = new BestVenueSelector();

    @Property
    void totalAllocatedQuantityNeverExceedsRequiredQuantity(
            @ForAll OrderSide side,
            @ForAll @LongRange(min = 1, max = 1000) long requiredQtyLong,
            @ForAll("validQuotes") List<BestVenueSelector.MarketQuoteView> quotes
    ) {
        BigDecimal requiredQty = BigDecimal.valueOf(requiredQtyLong);
        ListingSnapshot listing1 = listing(1, "XNAS");
        ListingSnapshot listing2 = listing(2, "XNYS");

        List<BestVenueSelector.RouteSlice> slices = selector.plan(
                side, null, List.of(listing1, listing2), quotes, requiredQty, BigDecimal.ONE);

        BigDecimal totalAllocated = slices.stream()
                .map(BestVenueSelector.RouteSlice::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(totalAllocated).isLessThanOrEqualTo(requiredQty);
    }

    @Property
    void buySlicesAreSortedInAscendingPriceOrder(
            @ForAll @LongRange(min = 10, max = 1000) long requiredQtyLong,
            @ForAll("validQuotes") List<BestVenueSelector.MarketQuoteView> quotes
    ) {
        BigDecimal requiredQty = BigDecimal.valueOf(requiredQtyLong);
        ListingSnapshot listing1 = listing(1, "XNAS");
        ListingSnapshot listing2 = listing(2, "XNYS");

        List<BestVenueSelector.RouteSlice> slices = selector.plan(
                OrderSide.BUY, null, List.of(listing1, listing2), quotes, requiredQty, BigDecimal.ONE);

        for (int i = 0; i < slices.size() - 1; i++) {
            BigDecimal priceCurrent = slices.get(i).price();
            BigDecimal priceNext = slices.get(i + 1).price();
            assertThat(priceCurrent).isLessThanOrEqualTo(priceNext);
        }
    }

    @Property
    void routedPricesNeverViolateLimitPrice(
            @ForAll OrderSide side,
            @ForAll @LongRange(min = 50, max = 150) long limitPriceLong,
            @ForAll("validQuotes") List<BestVenueSelector.MarketQuoteView> quotes
    ) {
        BigDecimal limitPrice = BigDecimal.valueOf(limitPriceLong);
        ListingSnapshot listing1 = listing(1, "XNAS");
        ListingSnapshot listing2 = listing(2, "XNYS");

        List<BestVenueSelector.RouteSlice> slices = selector.plan(
                side, limitPrice, List.of(listing1, listing2), quotes, new BigDecimal("100"), BigDecimal.ONE);

        for (BestVenueSelector.RouteSlice slice : slices) {
            if (side == OrderSide.BUY) {
                assertThat(slice.price()).isLessThanOrEqualTo(limitPrice);
            } else {
                assertThat(slice.price()).isGreaterThanOrEqualTo(limitPrice);
            }
        }
    }

    @Provide
    Arbitrary<List<BestVenueSelector.MarketQuoteView>> validQuotes() {
        Arbitrary<BestVenueSelector.MarketQuoteView> quoteArbitrary = Arbitraries.integers().between(1, 2).flatMap(id ->
                Arbitraries.integers().between(50, 150).flatMap(price ->
                        Arbitraries.integers().between(1, 100).map(depth ->
                                new BestVenueSelector.MarketQuoteView(
                                        id.longValue(),
                                        id == 1 ? "XNAS" : "XNYS",
                                        BigDecimal.valueOf(price - 1),
                                        BigDecimal.valueOf(depth),
                                        BigDecimal.valueOf(price),
                                        BigDecimal.valueOf(depth)
                                )
                        )
                )
        );
        return quoteArbitrary.list().ofMinSize(1).ofMaxSize(5);
    }

    private static ListingSnapshot listing(long id, String mic) {
        return new ListingSnapshot(
                id, 1, "AAPL", "Apple Inc.", "AAPL", mic, "Exchange",
                "US", "USD", new BigDecimal("0.01"), BigDecimal.ONE,
                new BigDecimal("100"), new BigDecimal("100")
        );
    }
}

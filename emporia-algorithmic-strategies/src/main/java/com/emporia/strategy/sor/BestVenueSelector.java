package com.emporia.strategy.sor;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Smart Order Routing (SOR) NBBO Selector.
 * Walks executable Level-2 depth across exchange listings in price-time priority order.
 */
public final class BestVenueSelector {

    public record RouteSlice(ListingSnapshot listing, BigDecimal price, BigDecimal quantity) {}

    public record Selection(ListingSnapshot listing, BigDecimal price) {}

    public Selection select(OrderSide side, BigDecimal limitPrice,
                             List<ListingSnapshot> listings, List<MarketQuoteView> quotes) {
        List<RouteSlice> plan = plan(side, limitPrice, listings, quotes, BigDecimal.ONE, BigDecimal.ONE);
        if (plan.isEmpty()) {
            throw new IllegalStateException("No executable venue quote exists for the instrument");
        }
        RouteSlice first = plan.get(0);
        return new Selection(first.listing(), first.price());
    }

    public List<RouteSlice> plan(OrderSide side, BigDecimal limitPrice,
                                  List<ListingSnapshot> listings, List<MarketQuoteView> quotes,
                                  BigDecimal requiredQuantity, BigDecimal sizeIncrement) {
        if (listings == null || listings.isEmpty() || quotes == null || quotes.isEmpty()
                || requiredQuantity == null || requiredQuantity.signum() <= 0) {
            return List.of();
        }

        Map<Long, ListingSnapshot> listingById = listings.stream()
                .collect(Collectors.toMap(ListingSnapshot::id, l -> l, (l1, l2) -> l1));
        Map<String, ListingSnapshot> listingByMic = listings.stream()
                .collect(Collectors.toMap(ListingSnapshot::exchangeMic, l -> l, (l1, l2) -> l1));

        List<VenueDepth> candidates = new ArrayList<>();
        for (MarketQuoteView quote : quotes) {
            ListingSnapshot listing = quote.listingId() > 0 ? listingById.get(quote.listingId()) : null;
            if (listing == null) {
                listing = listingByMic.get(quote.exchangeMic());
            }
            if (listing == null) continue;

            BigDecimal price = side == OrderSide.BUY ? quote.askPrice() : quote.bidPrice();
            BigDecimal depth = side == OrderSide.BUY ? quote.askSize() : quote.bidSize();

            if (price == null || price.signum() <= 0 || depth == null || depth.signum() <= 0) continue;

            if (limitPrice != null) {
                if (side == OrderSide.BUY && price.compareTo(limitPrice) > 0) continue;
                if (side == OrderSide.SELL && price.compareTo(limitPrice) < 0) continue;
            }
            candidates.add(new VenueDepth(listing, price, depth));
        }

        if (candidates.isEmpty()) return List.of();

        Comparator<VenueDepth> comparator = side == OrderSide.BUY
                ? Comparator.comparing(VenueDepth::price).thenComparing(v -> v.listing().exchangeMic())
                : Comparator.comparing(VenueDepth::price).reversed().thenComparing(v -> v.listing().exchangeMic());

        candidates.sort(comparator);

        List<RouteSlice> slices = new ArrayList<>();
        BigDecimal remaining = requiredQuantity;

        for (VenueDepth candidate : candidates) {
            if (remaining.signum() <= 0) break;

            BigDecimal alloc = remaining.min(candidate.depth());
            if (sizeIncrement != null && sizeIncrement.signum() > 0) {
                BigDecimal incrementCount = alloc.divideToIntegralValue(sizeIncrement);
                alloc = incrementCount.multiply(sizeIncrement);
            }

            if (alloc.signum() > 0) {
                slices.add(new RouteSlice(candidate.listing(), candidate.price(), alloc));
                remaining = remaining.subtract(alloc);
            }
        }

        return slices;
    }

    private record VenueDepth(ListingSnapshot listing, BigDecimal price, BigDecimal depth) {}

    public record MarketQuoteView(long listingId, String exchangeMic, BigDecimal bidPrice, BigDecimal bidSize, BigDecimal askPrice, BigDecimal askSize) {
        public MarketQuoteView(String exchangeMic, BigDecimal bidPrice, BigDecimal bidSize, BigDecimal askPrice, BigDecimal askSize) {
            this(0L, exchangeMic, bidPrice, bidSize, askPrice, askSize);
        }
    }
}

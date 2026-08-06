package com.emporia.execution;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.math.FixedPointMath;
import com.emporia.execution.TradingDataClient.DepthLevel;
import com.emporia.execution.TradingDataClient.MarketQuote;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds executable venue slices from the actual opposite-side depth. Reference
 * prices are intentionally not treated as liquidity.
 */
final class BestVenueSelector {
    Selection select(OrderSide side, BigDecimal limitPrice, List<ListingSnapshot> listings,
                     List<MarketQuote> quotes) {
        return candidates(side, limitPrice, listings, quotes).stream()
                .findFirst()
                .map(candidate -> new Selection(candidate.listing(), candidate.price()))
                .orElseThrow(() -> new IllegalStateException("No executable venue has market liquidity"));
    }

    List<RouteSlice> plan(OrderSide side, BigDecimal limitPrice, List<ListingSnapshot> listings,
                          List<MarketQuote> quotes, BigDecimal requestedQuantity,
                          BigDecimal sizeIncrement) {
        long remainingUnits;
        try {
            remainingUnits = FixedPointMath.exactUnits(requestedQuantity, sizeIncrement,
                    "Routed quantity");
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Routed quantity must align with a positive size increment", invalid);
        }
        List<RouteSlice> result = new ArrayList<>();
        for (Candidate candidate : candidates(side, limitPrice, listings, quotes)) {
            if (remainingUnits == 0) break;
            long candidateUnits = FixedPointMath.floorUnits(candidate.size(), sizeIncrement, "depth size");
            long executableUnits = Math.min(candidateUnits, remainingUnits);
            if (executableUnits <= 0) continue;
            result.add(new RouteSlice(candidate.listing(), candidate.price(),
                    FixedPointMath.unitsToBigDecimal(executableUnits, sizeIncrement, "route slice")));
            remainingUnits -= executableUnits;
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("No executable venue has market liquidity");
        }
        return List.copyOf(result);
    }

    private List<Candidate> candidates(OrderSide side, BigDecimal limitPrice,
                                       List<ListingSnapshot> listings, List<MarketQuote> quotes) {
        Map<Long, ListingSnapshot> byId = listings.stream()
                .filter(listing -> !"XOSR".equalsIgnoreCase(listing.exchangeMic()))
                .collect(Collectors.toMap(ListingSnapshot::id, Function.identity(), (left, right) -> left));
        List<Candidate> candidates = new ArrayList<>();
        for (MarketQuote quote : quotes) {
            List<DepthLevel> depth = side == OrderSide.BUY ? quote.offers() : quote.bids();
            for (DepthLevel level : depth) {
                ListingSnapshot listing = byId.get(level.listingId());
                if (listing == null) listing = byId.get(quote.listingId());
                if (listing == null || level.price() == null || level.price().signum() <= 0
                        || level.size() == null || level.size().signum() <= 0
                        || !withinLimit(side, level.price(), limitPrice)) {
                    continue;
                }
                candidates.add(new Candidate(listing, level.price(), level.size()));
            }
        }

        Comparator<Candidate> comparator = Comparator.comparing(Candidate::price);
        if (side == OrderSide.SELL) comparator = comparator.reversed();
        comparator = comparator.thenComparing(candidate -> candidate.listing().id())
                .thenComparing(candidate -> candidate.listing().exchangeMic());
        candidates.sort(comparator);
        return List.copyOf(candidates);
    }

    private boolean withinLimit(OrderSide side, BigDecimal price, BigDecimal limitPrice) {
        return limitPrice == null
                || side == OrderSide.BUY && price.compareTo(limitPrice) <= 0
                || side == OrderSide.SELL && price.compareTo(limitPrice) >= 0;
    }

    private record Candidate(ListingSnapshot listing, BigDecimal price, BigDecimal size) {
    }

    record Selection(ListingSnapshot listing, BigDecimal price) {
    }

    record RouteSlice(ListingSnapshot listing, BigDecimal price, BigDecimal quantity) {
    }
}

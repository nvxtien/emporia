package com.emporia.marketdata;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.marketdata.MarketDataService.DepthLevel;
import com.emporia.marketdata.MarketDataService.Quote;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Component
final class QuoteAggregator {

    Quote aggregate(ListingSnapshot composite, List<Quote> venueQuotes) {
        if (venueQuotes.isEmpty()) {
            return unavailable(composite, "No venue quote is available for " + composite.symbol());
        }

        Quote latest = venueQuotes.stream().max(Comparator.comparing(Quote::asOf)).orElseThrow();
        BigDecimal lastPrice = latest.lastPrice();
        BigDecimal change = lastPrice.subtract(composite.previousClose());
        BigDecimal changePercent = composite.previousClose().signum() == 0
                ? BigDecimal.ZERO
                : change.divide(composite.previousClose(), 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal volume = venueQuotes.stream().map(Quote::tradedVolume)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean interrupted = venueQuotes.stream().anyMatch(Quote::streamInterrupted);
        String status = venueQuotes.stream().map(Quote::streamStatusMessage)
                .filter(message -> message != null && !message.isBlank())
                .distinct().reduce((left, right) -> left + "; " + right).orElse("");

        return new Quote(composite.id(), composite.symbol(), composite.currency(), lastPrice, latest.lastQuantity(),
                composite.previousClose(), change, changePercent, volume,
                combinedDepth(venueQuotes, true), combinedDepth(venueQuotes, false), latest.asOf(), "AGGREGATED",
                interrupted, status);
    }

    private static Quote unavailable(ListingSnapshot listing, String message) {
        Instant now = Instant.now();
        return new Quote(listing.id(), listing.symbol(), listing.currency(), listing.referencePrice(), BigDecimal.ZERO,
                listing.previousClose(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(), now,
                "AGGREGATED", true, message);
    }

    private static List<DepthLevel> combinedDepth(List<Quote> quotes, boolean bids) {
        List<DepthLevel> result = new ArrayList<>();
        for (Quote quote : quotes) {
            List<DepthLevel> levels = bids ? quote.bids() : quote.offers();
            for (DepthLevel level : levels) {
                result.add(new DepthLevel(level.price(), level.size(), level.exchangeMic(), level.entryId(),
                        level.listingId() == 0 ? quote.listingId() : level.listingId()));
            }
        }
        Comparator<DepthLevel> byPrice = Comparator.comparing(DepthLevel::price);
        result.sort(bids ? byPrice.reversed() : byPrice);
        return List.copyOf(result);
    }
}

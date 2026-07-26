package com.emporia.marketdata;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.marketdata.MarketDataService.DepthLevel;
import com.emporia.marketdata.MarketDataService.Quote;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@ConditionalOnProperty(name = "emporia.market-data.provider", havingValue = "simulated", matchIfMissing = true)
public class SimulatedMarketDataProvider implements MarketDataProvider {

    @Override
    public List<Quote> quotes(List<ListingSnapshot> listings, Instant timestamp) {
        return listings.stream().map(listing -> simulatedQuote(listing, timestamp)).toList();
    }

    private Quote simulatedQuote(ListingSnapshot listing, Instant timestamp) {
        double phase = timestamp.toEpochMilli() / 14_000.0 + listing.id() * 0.83;
        double movement = Math.sin(phase) * 0.0026 + Math.cos(phase * 0.41) * 0.0011;
        BigDecimal middle = listing.referencePrice().multiply(BigDecimal.valueOf(1 + movement))
                .setScale(2, RoundingMode.HALF_UP);
        List<DepthLevel> bids = new ArrayList<>();
        List<DepthLevel> offers = new ArrayList<>();
        for (int level = 1; level <= 5; level++) {
            BigDecimal offset = listing.tickSize().multiply(BigDecimal.valueOf(level));
            long bidSize = 100L * (2 + Math.floorMod((int) listing.id() * 7 + level * 3, 18));
            long offerSize = 100L * (2 + Math.floorMod((int) listing.id() * 11 + level * 5, 18));
            bids.add(new DepthLevel(middle.subtract(offset), BigDecimal.valueOf(bidSize), listing.exchangeMic()));
            offers.add(new DepthLevel(middle.add(offset), BigDecimal.valueOf(offerSize), listing.exchangeMic()));
        }
        BigDecimal change = middle.subtract(listing.previousClose());
        BigDecimal changePercent = change.divide(listing.previousClose(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        long volume = 1_000_000L
                + Math.floorMod(listing.id() * 863_117L + timestamp.getEpochSecond() * 37L, 14_000_000L);
        return new Quote(listing.id(), listing.symbol(), listing.currency(), middle,
                BigDecimal.valueOf(100L * (1 + Math.floorMod(timestamp.getEpochSecond() + listing.id(), 10))),
                listing.previousClose(), change, changePercent, BigDecimal.valueOf(volume), bids, offers, timestamp,
                "SIMULATED");
    }
}

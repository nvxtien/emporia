package com.emporia.marketdata;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.marketdata.MarketDataService.Quote;

import java.time.Instant;
import java.util.List;

public interface MarketDataProvider {

    List<Quote> quotes(List<ListingSnapshot> listings, Instant timestamp);

    default Quote quote(ListingSnapshot listing, Instant timestamp) {
        return quotes(List.of(listing), timestamp).getFirst();
    }
}

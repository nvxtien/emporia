package com.emporia.marketdata;

import com.emporia.events.TradingEvents.ListingSnapshot;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MarketDataService {
    static final String COMPOSITE_EXCHANGE_MIC = "XOSR";

    private final StaticDataClient staticData;
    private final MarketDataProvider marketDataProvider;
    private final QuoteAggregator quoteAggregator;

    MarketDataService(StaticDataClient staticData, MarketDataProvider marketDataProvider, QuoteAggregator quoteAggregator) {
        this.staticData = staticData;
        this.marketDataProvider = marketDataProvider;
        this.quoteAggregator = quoteAggregator;
    }

    Quote getQuote(long listingId, String authorization) {
        ListingSnapshot listing = staticData.get(listingId, authorization);
        if (!isComposite(listing)) {
            return marketDataProvider.quote(listing, Instant.now());
        }
        return snapshot(resolve(List.of(listing), authorization), Instant.now()).getFirst();
    }

    List<Quote> getQuotes(List<Long> ids, String authorization) {
        return snapshot(resolveIds(ids, authorization), Instant.now());
    }

    ResolvedListings resolveIds(List<Long> ids, String authorization) {
        List<ListingSnapshot> listings = staticData.batch(ids, authorization).stream()
                .sorted(Comparator.comparingInt(listing -> ids.indexOf(listing.id())))
                .toList();
        return resolve(listings, authorization);
    }

    ResolvedListings resolve(List<ListingSnapshot> requested, String authorization) {
        List<String> compositeSymbols = requested.stream()
                .filter(MarketDataService::isComposite)
                .map(ListingSnapshot::symbol)
                .distinct()
                .toList();
        List<ListingSnapshot> underlying = compositeSymbols.isEmpty() ? List.of()
                : staticData.bySymbols(compositeSymbols, authorization).stream()
                                                                       .filter(candidate -> !isComposite(candidate))
                                                                       .toList();
        Map<Long, List<ListingSnapshot>> sources = new LinkedHashMap<>();
        for (ListingSnapshot listing : requested) {
            sources.put(listing.id(), isComposite(listing)
                    ? underlying.stream().filter(candidate -> candidate.symbol().equalsIgnoreCase(listing.symbol())).toList()
                    : List.of(listing));
        }
        return new ResolvedListings(List.copyOf(requested),
                Collections.unmodifiableMap(new LinkedHashMap<>(sources)));
    }

    List<Quote> snapshot(ResolvedListings resolved, Instant timestamp) {
        Map<Long, ListingSnapshot> providerListings = new LinkedHashMap<>();
        resolved.sources()
                .values()
                .stream()
                .flatMap(List::stream)
                .forEach(listing -> providerListings.put(listing.id(), listing));

        Map<Long, Quote> quotesByListing = marketDataProvider
                .quotes(new ArrayList<>(providerListings.values()), timestamp)
                .stream()
                .collect(Collectors.toMap(Quote::listingId, Function.identity(), (left, right) -> right));

        return resolved.requested().stream().map(listing -> {
            if (!isComposite(listing)) {
                return quotesByListing.get(listing.id());
            }
            List<Quote> venueQuotes = resolved.sources().getOrDefault(listing.id(), List.of()).stream()
                    .map(candidate -> quotesByListing.get(candidate.id()))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            return quoteAggregator.aggregate(listing, venueQuotes);
        }).toList();
    }

    ResolvedListings merge(List<ResolvedListings> parts) {
        Map<Long, ListingSnapshot> requested = new LinkedHashMap<>();
        Map<Long, List<ListingSnapshot>> sources = new LinkedHashMap<>();
        for (ResolvedListings part : parts) {
            part.requested().forEach(listing -> requested.put(listing.id(), listing));
            sources.putAll(part.sources());
        }
        return new ResolvedListings(List.copyOf(requested.values()),
                Collections.unmodifiableMap(new LinkedHashMap<>(sources)));
    }

    private static boolean isComposite(ListingSnapshot listing) {
        return COMPOSITE_EXCHANGE_MIC.equalsIgnoreCase(listing.exchangeMic());
    }

    record ResolvedListings(List<ListingSnapshot> requested, Map<Long, List<ListingSnapshot>> sources) { }

    record DepthLevel(BigDecimal price, BigDecimal size, String exchangeMic, String entryId, long listingId) {
        DepthLevel(BigDecimal price, BigDecimal size, String exchangeMic) {
            this(price, size, exchangeMic, "", 0);
        }
    }

    record Quote(long listingId, String symbol, String currency, BigDecimal lastPrice, BigDecimal lastQuantity,
                 BigDecimal previousClose, BigDecimal change, BigDecimal changePercent, BigDecimal tradedVolume,
                 List<DepthLevel> bids, List<DepthLevel> offers, Instant asOf, String source,
                 boolean streamInterrupted, String streamStatusMessage) {
        Quote(long listingId, String symbol, String currency, BigDecimal lastPrice, BigDecimal lastQuantity,
              BigDecimal previousClose, BigDecimal change, BigDecimal changePercent, BigDecimal tradedVolume,
              List<DepthLevel> bids, List<DepthLevel> offers, Instant asOf, String source) {
            this(listingId, symbol, currency, lastPrice, lastQuantity, previousClose, change, changePercent,
                    tradedVolume, bids, offers, asOf, source, false, "");
        }

        Quote interrupted(String message, Instant timestamp) {
            return new Quote(listingId, symbol, currency, lastPrice, lastQuantity, previousClose, change,
                    changePercent, tradedVolume, List.of(), List.of(), timestamp, source, true, message);
        }
    }
}

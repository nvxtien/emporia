package com.emporia.userpreferences;

import com.emporia.events.TradingEvents.ListingSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
class WatchlistService {
    private static final List<String> DEFAULT_SYMBOLS = List.of("AAPL", "NVDA", "MSFT", "TSLA");
    private final WatchlistEntryRepository entries;
    private final StaticDataClient staticData;

    WatchlistService(WatchlistEntryRepository entries, StaticDataClient staticData) {
        this.entries = entries;
        this.staticData = staticData;
    }

    @Transactional
    List<WatchlistItem> get(String userSubject, String authorization) {
        if (entries.countByUserSubject(userSubject) == 0) {
            int position = 0;
            Map<String, ListingSnapshot> preferred = new java.util.LinkedHashMap<>();
            for (ListingSnapshot listing : staticData.bySymbols(DEFAULT_SYMBOLS, authorization)) {
                preferred.merge(listing.symbol().toUpperCase(java.util.Locale.ROOT), listing,
                        (current, candidate) -> preferredListing(current, candidate));
            }
            for (ListingSnapshot listing : preferred.values()) {
                entries.insertIfAbsent(java.util.UUID.randomUUID(), userSubject, listing.id(), position, Instant.now());
                position++;
            }
            entries.flush();
        }
        List<WatchlistEntry> stored = entries.findByUserSubjectOrderByDisplayOrderAscAddedAtAsc(userSubject);
        Map<Long, ListingSnapshot> listings = new HashMap<>();
        staticData.batch(stored.stream().map(WatchlistEntry::listingId).toList(), authorization)
                .forEach(listing -> listings.put(listing.id(), listing));
        return stored.stream().filter(entry -> listings.containsKey(entry.listingId()))
                .map(entry -> WatchlistItem.from(entry, listings.get(entry.listingId()))).toList();
    }

    @Transactional
    WatchlistItem add(String userSubject, long listingId, String authorization) {
        ListingSnapshot listing = staticData.get(listingId, authorization);
        entries.insertIfAbsent(java.util.UUID.randomUUID(), userSubject, listingId,
                Math.toIntExact(entries.countByUserSubject(userSubject)), Instant.now());
        WatchlistEntry entry = entries.findByUserSubjectAndListingId(userSubject, listingId)
                .orElseThrow(() -> new IllegalStateException("Watchlist entry was not stored"));
        return WatchlistItem.from(entry, listing);
    }

    @Transactional
    void remove(String userSubject, long listingId) {
        entries.deleteByUserSubjectAndListingId(userSubject, listingId);
    }

    private static ListingSnapshot preferredListing(ListingSnapshot current, ListingSnapshot candidate) {
        if ("XOSR".equalsIgnoreCase(candidate.exchangeMic())) return candidate;
        if ("XOSR".equalsIgnoreCase(current.exchangeMic())) return current;
        return current.id() <= candidate.id() ? current : candidate;
    }

    record WatchlistItem(String id, int displayOrder, Instant addedAt, ListingSnapshot listing) {
        static WatchlistItem from(WatchlistEntry entry, ListingSnapshot listing) {
            return new WatchlistItem(entry.id().toString(), entry.displayOrder(), entry.addedAt(), listing);
        }
    }
}

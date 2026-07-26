package com.emporia.staticdata;

import com.emporia.events.TradingEvents.ListingSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/instruments")
class InstrumentController {
    private final InstrumentListingRepository listings;

    InstrumentController(InstrumentListingRepository listings) {
        this.listings = listings;
    }

    @GetMapping
    List<ListingSnapshot> search(@RequestParam(defaultValue = "") String query) {
        return listings.search(query.strip()).stream().limit(50).map(InstrumentListing::snapshot).toList();
    }

    @GetMapping("/{listingId}")
    ListingSnapshot get(@PathVariable long listingId) {
        return listings.findById(listingId).filter(InstrumentListing::isEnabled)
                .map(InstrumentListing::snapshot)
                .orElseThrow(() -> new InstrumentNotFoundException("Instrument listing not found"));
    }

    @GetMapping("/batch")
    List<ListingSnapshot> batch(@RequestParam List<Long> ids) {
        return listings.findByIdInAndEnabledTrue(ids).stream()
                .sorted((left, right) -> Integer.compare(ids.indexOf(left.snapshot().id()), ids.indexOf(right.snapshot().id())))
                .map(InstrumentListing::snapshot).toList();
    }

    @GetMapping("/by-symbols")
    List<ListingSnapshot> bySymbols(@RequestParam List<String> symbols) {
        return listings.findBySymbolInAndEnabledTrueOrderById(symbols).stream().map(InstrumentListing::snapshot).toList();
    }

    @GetMapping("/{listingId}/same-instrument")
    List<ListingSnapshot> sameInstrument(@PathVariable long listingId) {
        InstrumentListing listing = listings.findById(listingId).filter(InstrumentListing::isEnabled)
                .orElseThrow(() -> new InstrumentNotFoundException("Instrument listing not found"));
        return listings.findBySymbolIgnoreCaseAndEnabledTrueOrderById(listing.snapshot().symbol()).stream()
                .map(InstrumentListing::snapshot).toList();
    }

    @GetMapping("/match")
    ListingSnapshot match(@RequestParam String symbol, @RequestParam String mic) {
        return listings.findFirstByMarketSymbolIgnoreCaseAndExchangeMicIgnoreCaseAndEnabledTrue(symbol.strip(), mic.strip())
                .map(InstrumentListing::snapshot)
                .orElseThrow(() -> new InstrumentNotFoundException("Instrument listing not found"));
    }

    @GetMapping("/matching")
    List<ListingSnapshot> matching(@RequestParam(defaultValue = "") String prefix) {
        return listings.findBySymbolPrefix(prefix.strip()).stream().limit(50).map(InstrumentListing::snapshot).toList();
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    static class InstrumentNotFoundException extends RuntimeException {
        InstrumentNotFoundException(String message) { super(message); }
    }
}

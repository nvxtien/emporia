package com.emporia.marketdata;

import com.emporia.marketdata.MarketDataService.Quote;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/market-data")
public class MarketDataController {
    private final MarketDataService marketData;
    private final MarketDataStreamService streams;

    MarketDataController(MarketDataService marketData, MarketDataStreamService streams) {
        this.marketData = marketData;
        this.streams = streams;
    }

    @GetMapping("/quotes")
    List<Quote> quotes(@RequestParam List<Long> listingIds,
                       @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return marketData.getQuotes(listingIds.stream().distinct().limit(50).toList(), authorization);
    }

    @GetMapping("/{listingId}/depth")
    Quote depth(@PathVariable long listingId, @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return marketData.getQuote(listingId, authorization);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @SuppressWarnings("PMD.CloseResource") // The SSE callbacks take ownership and close the subscription.
    SseEmitter stream(@RequestParam List<Long> listingIds,
                      @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        List<Long> ids = listingIds.stream().distinct().limit(100).toList();
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one listing ID is required");
        }

        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<ConflatedQuoteSubscription> subscription = new AtomicReference<>();
        ConflatedQuoteSubscription created = streams.subscribe(ids, authorization,
                new ConflatedQuoteSubscription.Sink() {
                    @Override
                    public void send(Quote quote) throws Exception {
                        emitter.send(SseEmitter.event()
                                .id(quote.listingId() + ":" + quote.asOf().toEpochMilli())
                                .name("quote")
                                .reconnectTime(1_000)
                                .data(quote));
                    }

                    @Override
                    public void failed(Throwable error) {
                        emitter.completeWithError(error);
                    }
                });
        subscription.set(created);
        emitter.onCompletion(() -> close(subscription));
        emitter.onTimeout(() -> close(subscription));
        emitter.onError(ignored -> close(subscription));
        return emitter;
    }

    private static void close(AtomicReference<ConflatedQuoteSubscription> subscription) {
        ConflatedQuoteSubscription current = subscription.getAndSet(null);
        if (current != null) {
            try (current) {
                // Closing the transferred subscription stops its worker.
            }
        }
    }
}

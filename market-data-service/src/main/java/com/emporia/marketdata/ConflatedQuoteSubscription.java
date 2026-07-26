package com.emporia.marketdata;

import com.emporia.marketdata.MarketDataService.Quote;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class ConflatedQuoteSubscription implements AutoCloseable {
    interface Sink {
        void send(Quote quote) throws Exception;

        default void failed(Throwable error) {
        }
    }

    private final AtomicReference<MarketDataService.ResolvedListings> resolved;
    private final Sink sink;
    private final Runnable onClosed;
    private final Consumer<Quote> onConflated;
    private final Map<Long, Quote> latestByListing = new ConcurrentHashMap<>();
    private final Semaphore available = new Semaphore(0);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;

    ConflatedQuoteSubscription(MarketDataService.ResolvedListings resolved, Sink sink, Runnable onClosed,
                               Consumer<Quote> onConflated) {
        this.resolved = new AtomicReference<>(resolved);
        this.sink = sink;
        this.onClosed = onClosed;
        this.onConflated = onConflated;
        this.worker = Thread.ofVirtual().name("market-data-subscriber-", 0).start(this::drain);
    }

    MarketDataService.ResolvedListings resolved() {
        return resolved.get();
    }

    void resolved(MarketDataService.ResolvedListings value) {
        resolved.set(value);
    }

    boolean accepts(long listingId) {
        return resolved.get().sources().containsKey(listingId);
    }

    void offer(Quote quote) {
        if (!running.get() || !accepts(quote.listingId())) {
            return;
        }
        Quote replaced = latestByListing.put(quote.listingId(), quote);
        if (replaced != null) {
            onConflated.accept(replaced);
        }
        available.release();
    }

    private void drain() {
        try {
            while (running.get()) {
                available.acquire();
                for (Quote quote : latestByListing.values().stream()
                        .sorted(Comparator.comparingLong(Quote::listingId)).toList()) {
                    if (latestByListing.remove(quote.listingId(), quote)) {
                        sink.send(quote);
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            sink.failed(error);
        } finally {
            if (running.getAndSet(false)) {
                onClosed.run();
            }
        }
    }

    @Override
    public void close() {
        if (running.getAndSet(false)) {
            latestByListing.clear();
            worker.interrupt();
            onClosed.run();
        }
    }
}

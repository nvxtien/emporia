package com.emporia.marketdata;

import com.emporia.marketdata.MarketDataService.Quote;
import com.emporia.marketdata.MarketDataService.ResolvedListings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class MarketDataStreamService {
    private final MarketDataService marketData;
    private final Duration publishInterval;
    private final Duration heartbeatInterval;
    private final Map<UUID, ConflatedQuoteSubscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<Long, Quote> lastQuotes = new ConcurrentHashMap<>();
    private final Map<Long, Instant> lastPublishedAt = new ConcurrentHashMap<>();
    private final ScheduledExecutorService publisher = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "market-data-publisher");
        thread.setDaemon(true);
        return thread;
    });
    private final Counter quotesPublished;
    private final Counter quotesConflated;
    private final Counter providerFailures;
    private final AtomicBoolean started = new AtomicBoolean();

    MarketDataStreamService(MarketDataService marketData, MeterRegistry meters,
                            @Value("${emporia.market-data.stream.publish-interval:250ms}") Duration publishInterval,
                            @Value("${emporia.market-data.stream.heartbeat-interval:5s}") Duration heartbeatInterval) {
        this.marketData = marketData;
        this.publishInterval = publishInterval;
        this.heartbeatInterval = heartbeatInterval;
        this.quotesPublished = meters.counter("emporia.market.data.quotes.published");
        this.quotesConflated = meters.counter("emporia.market.data.quotes.conflated");
        this.providerFailures = meters.counter("emporia.market.data.provider.failures");
        Gauge.builder("emporia.market.data.subscribers", subscriptions, Map::size).register(meters);
    }

    ConflatedQuoteSubscription subscribe(List<Long> listingIds, String authorization,
                                         ConflatedQuoteSubscription.Sink sink) {
        ResolvedListings resolved = marketData.resolveIds(listingIds, authorization);
        return subscribe(resolved, sink);
    }

    ConflatedQuoteSubscription subscribe(ResolvedListings resolved, ConflatedQuoteSubscription.Sink sink) {
        startPublisher();
        UUID id = UUID.randomUUID();
        ConflatedQuoteSubscription[] reference = new ConflatedQuoteSubscription[1];
        ConflatedQuoteSubscription subscription = new ConflatedQuoteSubscription(
                resolved,
                sink,
                () -> subscriptions.remove(id, reference[0]),
                ignored -> quotesConflated.increment()
        );
        reference[0] = subscription;
        subscriptions.put(id, subscription);
        publishInitial(subscription);
        return subscription;
    }

    void addListing(ConflatedQuoteSubscription subscription, long listingId, String authorization) {
        ResolvedListings addition = marketData.resolveIds(List.of(listingId), authorization);
        subscription.resolved(marketData.merge(List.of(subscription.resolved(), addition)));
        marketData.snapshot(addition, Instant.now()).forEach(subscription::offer);
    }

    int activeSubscribers() {
        return subscriptions.size();
    }

    private void startPublisher() {
        if (started.compareAndSet(false, true)) {
            long intervalMillis = Math.max(10, publishInterval.toMillis());
            publisher.scheduleWithFixedDelay(this::publish, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void publishInitial(ConflatedQuoteSubscription subscription) {
        marketData.snapshot(subscription.resolved(), Instant.now()).forEach(quote -> {
            lastQuotes.put(quote.listingId(), quote);
            lastPublishedAt.put(quote.listingId(), Instant.now());
            subscription.offer(quote);
            quotesPublished.increment();
        });
    }

    @SuppressWarnings("PMD.CloseResource") // Iteration borrows service-owned subscriptions; shutdown closes them.
    private void publish() {
        List<ConflatedQuoteSubscription> current = List.copyOf(subscriptions.values());
        if (current.isEmpty()) {
            return;
        }
        try {
            ResolvedListings resolved = marketData.merge(current.stream()
                    .map(ConflatedQuoteSubscription::resolved).toList());
            List<Quote> quotes = marketData.snapshot(resolved, Instant.now());
            for (Quote quote : quotes) {
                Quote previous = lastQuotes.put(quote.listingId(), quote);
                Instant now = Instant.now();
                Instant lastPublished = lastPublishedAt.getOrDefault(quote.listingId(), Instant.EPOCH);
                if (quote.equals(previous) && lastPublished.plus(heartbeatInterval).isAfter(now)) {
                    continue;
                }
                lastPublishedAt.put(quote.listingId(), now);
                current.forEach(subscription -> subscription.offer(quote));
                quotesPublished.increment();
            }
        } catch (RuntimeException error) {
            providerFailures.increment();
            Instant now = Instant.now();
            for (ConflatedQuoteSubscription subscription : current) {
                lastQuotes.values().stream()
                        .filter(quote -> subscription.accepts(quote.listingId()))
                        .map(quote -> quote.interrupted(error.getMessage(), now))
                        .forEach(subscription::offer);
            }
        }
    }

    private void stopPublisher() {
        subscriptions.values().forEach(ConflatedQuoteSubscription::close);
        subscriptions.clear();
        publisher.shutdownNow();
    }

    @PreDestroy
    void shutdown() {
        stopPublisher();
    }
}

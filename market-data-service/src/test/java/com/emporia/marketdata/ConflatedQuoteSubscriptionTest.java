package com.emporia.marketdata;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.marketdata.MarketDataService.Quote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ConflatedQuoteSubscriptionTest {

    @Test
    void slowConsumerReceivesTheNewestPendingQuoteInsteadOfEveryStaleUpdate() throws Exception {
        ListingSnapshot listing = listing();
        MarketDataService.ResolvedListings resolved =
                new MarketDataService.ResolvedListings(List.of(listing), Map.of(1L, List.of(listing)));
        CountDownLatch firstSendStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSend = new CountDownLatch(1);
        CountDownLatch twoQuotesSent = new CountDownLatch(2);
        List<BigDecimal> sentPrices = new CopyOnWriteArrayList<>();
        List<Quote> conflated = new CopyOnWriteArrayList<>();

        try (ConflatedQuoteSubscription subscription = new ConflatedQuoteSubscription(resolved, quote -> {
            sentPrices.add(quote.lastPrice());
            if (firstSendStarted.getCount() > 0) {
                firstSendStarted.countDown();
                assertThat(releaseFirstSend.await(2, TimeUnit.SECONDS)).isTrue();
            }
            twoQuotesSent.countDown();
        }, () -> { }, conflated::add)) {
            subscription.offer(quote("1"));
            assertThat(firstSendStarted.await(2, TimeUnit.SECONDS)).isTrue();

            subscription.offer(quote("2"));
            subscription.offer(quote("3"));
            releaseFirstSend.countDown();

            assertThat(twoQuotesSent.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(sentPrices).containsExactly(new BigDecimal("1"), new BigDecimal("3"));
            assertThat(conflated).extracting(Quote::lastPrice).containsExactly(new BigDecimal("2"));
        }
    }

    private static ListingSnapshot listing() {
        return new ListingSnapshot(1, 1, "AAPL", "Apple", "AAPL", "XNAS", "Nasdaq", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("200"), new BigDecimal("198"));
    }

    private static Quote quote(String price) {
        return new Quote(1, "AAPL", "USD", new BigDecimal(price), BigDecimal.ONE, new BigDecimal("198"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(), Instant.now(), "TEST");
    }
}

package com.emporia.marketdata;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.marketdata.MarketDataService.Quote;
import com.ettech.fixmarketsimulator.marketdataserver.api.FixSimMarketDataServiceGrpc;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.fixprotocol.components.Fix;
import org.fixprotocol.components.Instrument;
import org.fixprotocol.components.MarketData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class FixSimulatorMarketDataProviderTest {
    private final AtomicReference<StreamObserver<MarketData.MarketDataIncrementalRefresh>> updates =
            new AtomicReference<>();
    private final CountDownLatch subscribed = new CountDownLatch(1);
    private Server server;
    private FixSimulatorMarketDataProvider provider;

    @BeforeEach
    void startFixSimulator() throws Exception {
        server = NettyServerBuilder.forPort(0)
                .addService(new FixSimMarketDataServiceGrpc.FixSimMarketDataServiceImplBase() {
                    @Override
                    public StreamObserver<MarketData.MarketDataRequest> connect(
                            StreamObserver<MarketData.MarketDataIncrementalRefresh> responseObserver) {
                        updates.set(responseObserver);
                        return new StreamObserver<>() {
                            @Override
                            public void onNext(MarketData.MarketDataRequest request) {
                                if (request.getInstrmtMdReqGrp(0).getInstrument().getSymbol().equals("AAPL")) {
                                    subscribed.countDown();
                                }
                            }

                            @Override
                            public void onError(Throwable error) {
                            }

                            @Override
                            public void onCompleted() {
                            }
                        };
                    }
                }).build().start();
        provider = new FixSimulatorMarketDataProvider("test-client",
                "XNAS=localhost:" + server.getPort(), Duration.ofSeconds(2), Duration.ofMinutes(1));
        provider.start();
    }

    @AfterEach
    void stopFixSimulator() {
        provider.stop();
        server.shutdownNow();
    }

    @Test
    void maintainsAFullIncrementalBookAndStreamStatus() throws Exception {
        CompletableFuture<List<Quote>> initial = CompletableFuture.supplyAsync(
                () -> provider.quotes(List.of(listing()), Instant.now()));
        assertThat(subscribed.await(2, TimeUnit.SECONDS)).isTrue();

        publish(
                update(MarketData.MDEntryTypeEnum.MD_ENTRY_TYPE_BID,
                        MarketData.MDUpdateActionEnum.MD_UPDATE_ACTION_NEW, "bid-1", "199.10", "200"),
                update(MarketData.MDEntryTypeEnum.MD_ENTRY_TYPE_BID,
                        MarketData.MDUpdateActionEnum.MD_UPDATE_ACTION_NEW, "bid-2", "199.00", "300"),
                update(MarketData.MDEntryTypeEnum.MD_ENTRY_TYPE_OFFER,
                        MarketData.MDUpdateActionEnum.MD_UPDATE_ACTION_NEW, "ask-1", "199.20", "400"),
                update(MarketData.MDEntryTypeEnum.MD_ENTRY_TYPE_TRADE,
                        MarketData.MDUpdateActionEnum.MD_UPDATE_ACTION_NEW, "trade", "199.15", "25"),
                update(MarketData.MDEntryTypeEnum.MD_ENTRY_TYPE_TRADE_VOLUME,
                        MarketData.MDUpdateActionEnum.MD_UPDATE_ACTION_NEW, "volume", "0", "1000")
        );
        initial.get(2, TimeUnit.SECONDS);
        Quote first = current();
        assertThat(first.bids()).hasSize(2);
        assertThat(first.bids().get(0).price()).isEqualByComparingTo("199.10");
        assertThat(first.bids().get(1).price()).isEqualByComparingTo("199.00");
        assertThat(first.offers()).singleElement()
                .extracting(MarketDataService.DepthLevel::entryId).isEqualTo("ask-1");
        assertThat(first.lastPrice()).isEqualByComparingTo("199.15");
        assertThat(first.tradedVolume()).isEqualByComparingTo("1000");

        publish(
                update(MarketData.MDEntryTypeEnum.MD_ENTRY_TYPE_BID,
                        MarketData.MDUpdateActionEnum.MD_UPDATE_ACTION_CHANGE, "bid-2", "199.12", "350"),
                update(MarketData.MDEntryTypeEnum.MD_ENTRY_TYPE_BID,
                        MarketData.MDUpdateActionEnum.MD_UPDATE_ACTION_DELETE, "bid-1", "199.10", "0")
        );
        Quote changed = awaitQuote(quote -> quote.bids().size() == 1
                && quote.bids().getFirst().entryId().equals("bid-2"));
        assertThat(changed.bids()).singleElement().satisfies(level -> {
            assertThat(level.entryId()).isEqualTo("bid-2");
            assertThat(level.price()).isEqualByComparingTo("199.12");
            assertThat(level.size()).isEqualByComparingTo("350");
        });

        updates.get().onError(new IllegalStateException("test disconnect"));
        Quote interrupted = awaitQuote(Quote::streamInterrupted);
        assertThat(interrupted.streamInterrupted()).isTrue();
        assertThat(interrupted.streamStatusMessage()).contains("FIX simulator stream interrupted");
    }

    private Quote current() {
        return provider.quotes(List.of(listing()), Instant.now()).getFirst();
    }

    private Quote awaitQuote(Predicate<Quote> condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        Quote quote;
        do {
            quote = current();
            if (condition.test(quote)) {
                return quote;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        return quote;
    }

    private void publish(MarketData.MDIncGrp... groups) {
        updates.get().onNext(MarketData.MarketDataIncrementalRefresh.newBuilder()
                .addAllMdIncGrp(List.of(groups)).build());
    }

    private static MarketData.MDIncGrp update(MarketData.MDEntryTypeEnum type,
                                               MarketData.MDUpdateActionEnum action,
                                               String entryId, String price, String size) {
        return MarketData.MDIncGrp.newBuilder()
                .setInstrument(Instrument.newBuilder().setSymbol("AAPL"))
                .setMdEntryType(type)
                .setMdUpdateAction(action)
                .setMdEntryId(entryId)
                .setMdEntryPx(decimal(price))
                .setMdEntrySize(decimal(size))
                .build();
    }

    private static Fix.Decimal64 decimal(String value) {
        BigDecimal decimal = new BigDecimal(value).stripTrailingZeros();
        return Fix.Decimal64.newBuilder()
                .setMantissa(decimal.unscaledValue().longValueExact())
                .setExponent(-decimal.scale())
                .build();
    }

    private static ListingSnapshot listing() {
        return new ListingSnapshot(1, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("200"), new BigDecimal("198"));
    }
}

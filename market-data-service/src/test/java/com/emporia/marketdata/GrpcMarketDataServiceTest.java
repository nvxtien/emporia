package com.emporia.marketdata;

import com.emporia.marketdata.grpc.api.MdsConnectRequest;
import com.emporia.marketdata.grpc.api.MdsSubscribeRequest;
import com.emporia.marketdata.grpc.model.ClobQuote;
import com.emporia.marketdata.grpc.model.Empty;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GrpcMarketDataServiceTest {
    private final MarketDataStreamService streams = mock(MarketDataStreamService.class);
    private final ServiceAccessTokenProvider tokens = mock(ServiceAccessTokenProvider.class);
    private final ConflatedQuoteSubscription subscription = mock(ConflatedQuoteSubscription.class);
    private final GrpcMarketDataService service = new GrpcMarketDataService(streams, tokens);

    @Test
    void publishesQuotesUsingTheLegacyGoGrpcContract() throws Exception {
        when(streams.subscribe(any(MarketDataService.ResolvedListings.class), any())).thenReturn(subscription);
        RecordingObserver<ClobQuote> observer = new RecordingObserver<>();

        service.connect(MdsConnectRequest.newBuilder().setSubscriberId("smart-router-1").build(), observer);

        ArgumentCaptor<ConflatedQuoteSubscription.Sink> sink =
                ArgumentCaptor.forClass(ConflatedQuoteSubscription.Sink.class);
        verify(streams).subscribe(any(MarketDataService.ResolvedListings.class), sink.capture());
        sink.getValue().send(quote());
        assertThat(observer.values).singleElement().satisfies(result -> {
            assertThat(result.getListingId()).isEqualTo(1);
            assertThat(result.getLastPrice().getMantissa()).isEqualTo(19915);
        });
    }

    @Test
    void subscribesAConnectedGoClientUsingAServiceAccessToken() {
        when(streams.subscribe(any(MarketDataService.ResolvedListings.class), any())).thenReturn(subscription);
        when(tokens.authorizationHeader()).thenReturn("Bearer service-token");
        RecordingObserver<ClobQuote> quotes = new RecordingObserver<>();
        RecordingObserver<Empty> result = new RecordingObserver<>();
        service.connect(MdsConnectRequest.newBuilder().setSubscriberId("smart-router-1").build(), quotes);

        service.subscribe(MdsSubscribeRequest.newBuilder()
                .setSubscriberId("smart-router-1").setListingId(42).build(), result);

        verify(streams).addListing(subscription, 42, "Bearer service-token");
        assertThat(result.values).containsExactly(Empty.getDefaultInstance());
        assertThat(result.completed).isTrue();
    }

    @Test
    void connectRejectsEmptySubscriberId() {
        RecordingObserver<ClobQuote> observer = new RecordingObserver<>();
        service.connect(MdsConnectRequest.newBuilder().setSubscriberId("   ").build(), observer);
        assertThat(observer.error).isNotNull();
    }

    @Test
    void subscribeRejectsUnknownSubscriber() {
        RecordingObserver<Empty> result = new RecordingObserver<>();
        service.subscribe(MdsSubscribeRequest.newBuilder()
                .setSubscriberId("non-existent").setListingId(42).build(), result);
        assertThat(result.error).isNotNull();
    }

    @Test
    void subscribeHandlesAddListingFailure() {
        when(streams.subscribe(any(MarketDataService.ResolvedListings.class), any())).thenReturn(subscription);
        when(tokens.authorizationHeader()).thenReturn("Bearer token");
        RecordingObserver<ClobQuote> quotes = new RecordingObserver<>();
        RecordingObserver<Empty> result = new RecordingObserver<>();
        service.connect(MdsConnectRequest.newBuilder().setSubscriberId("client-1").build(), quotes);

        org.mockito.Mockito.doThrow(new RuntimeException("stream error"))
                .when(streams).addListing(subscription, 42, "Bearer token");

        service.subscribe(MdsSubscribeRequest.newBuilder()
                .setSubscriberId("client-1").setListingId(42).build(), result);
        assertThat(result.error).isNotNull();
    }

    @Test
    void sinkFailedTriggersObserverError() {
        when(streams.subscribe(any(MarketDataService.ResolvedListings.class), any())).thenReturn(subscription);
        RecordingObserver<ClobQuote> observer = new RecordingObserver<>();
        service.connect(MdsConnectRequest.newBuilder().setSubscriberId("client-1").build(), observer);

        ArgumentCaptor<ConflatedQuoteSubscription.Sink> sink =
                ArgumentCaptor.forClass(ConflatedQuoteSubscription.Sink.class);
        verify(streams).subscribe(any(MarketDataService.ResolvedListings.class), sink.capture());

        sink.getValue().failed(new RuntimeException("sink failed"));
        assertThat(observer.error).isNotNull();
    }

    @Test
    void reconnectingClientClosesPreviousSubscription() {
        ConflatedQuoteSubscription sub1 = mock(ConflatedQuoteSubscription.class);
        ConflatedQuoteSubscription sub2 = mock(ConflatedQuoteSubscription.class);
        when(streams.subscribe(any(), any())).thenReturn(sub1).thenReturn(sub2);

        RecordingObserver<ClobQuote> obs1 = new RecordingObserver<>();
        RecordingObserver<ClobQuote> obs2 = new RecordingObserver<>();

        service.connect(MdsConnectRequest.newBuilder().setSubscriberId("same-client").build(), obs1);
        service.connect(MdsConnectRequest.newBuilder().setSubscriberId("same-client").build(), obs2);

        verify(sub1).close();
        assertThat(obs1.completed).isTrue();
    }

    private static MarketDataService.Quote quote() {
        return new MarketDataService.Quote(1, "AAPL", "USD", new BigDecimal("199.15"), BigDecimal.ONE,
                new BigDecimal("198"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN,
                List.of(), List.of(), Instant.parse("2026-07-23T10:00:00Z"), "TEST");
    }

    private static final class RecordingObserver<T> implements StreamObserver<T> {
        private final List<T> values = new ArrayList<>();
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(T value) {
            values.add(value);
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }
}

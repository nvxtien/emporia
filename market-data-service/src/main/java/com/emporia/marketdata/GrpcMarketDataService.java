package com.emporia.marketdata;

import com.emporia.marketdata.grpc.api.MarketDataServiceGrpc;
import com.emporia.marketdata.grpc.api.MdsConnectRequest;
import com.emporia.marketdata.grpc.api.MdsSubscribeRequest;
import com.emporia.marketdata.grpc.model.Empty;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
final class GrpcMarketDataService extends MarketDataServiceGrpc.MarketDataServiceImplBase {
    private final MarketDataStreamService streams;
    private final ServiceAccessTokenProvider serviceTokens;
    private final Map<String, GrpcClient> clients = new ConcurrentHashMap<>();

    GrpcMarketDataService(MarketDataStreamService streams, ServiceAccessTokenProvider serviceTokens) {
        this.streams = streams;
        this.serviceTokens = serviceTokens;
    }

    @Override
    @SuppressWarnings("PMD.CloseResource") // GrpcClient owns the subscription until replacement or cancellation.
    public void connect(MdsConnectRequest request, StreamObserver<com.emporia.marketdata.grpc.model.ClobQuote> observer) {
        String subscriberId = request.getSubscriberId().strip();
        if (subscriberId.isEmpty()) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("subscriberId is required").asRuntimeException());
            return;
        }

        AtomicReference<GrpcClient> reference = new AtomicReference<>();
        ConflatedQuoteSubscription subscription = streams.subscribe(
                new MarketDataService.ResolvedListings(List.of(), Map.of()),
                new ConflatedQuoteSubscription.Sink() {
                    @Override
                    public void send(MarketDataService.Quote quote) {
                        observer.onNext(GrpcQuoteConverter.convert(quote));
                    }

                    @Override
                    public void failed(Throwable error) {
                        observer.onError(Status.UNAVAILABLE.withDescription(error.getMessage())
                                .withCause(error).asRuntimeException());
                    }
                });
        GrpcClient client = new GrpcClient(subscription, observer);
        reference.set(client);
        GrpcClient previous = clients.put(subscriberId, client);
        if (previous != null) {
            previous.close();
        }
        if (observer instanceof ServerCallStreamObserver<?> serverObserver) {
            serverObserver.setOnCancelHandler(() -> remove(subscriberId, reference.get()));
        }
    }

    @Override
    public void subscribe(MdsSubscribeRequest request, StreamObserver<Empty> observer) {
        GrpcClient client = clients.get(request.getSubscriberId());
        if (client == null) {
            observer.onError(Status.FAILED_PRECONDITION
                    .withDescription("No connection exists for subscriber " + request.getSubscriberId())
                    .asRuntimeException());
            return;
        }
        try {
            streams.addListing(client.subscription(), request.getListingId(), serviceTokens.authorizationHeader());
            observer.onNext(Empty.getDefaultInstance());
            observer.onCompleted();
        } catch (RuntimeException error) {
            observer.onError(Status.UNAVAILABLE.withDescription(error.getMessage()).withCause(error).asRuntimeException());
        }
    }

    private void remove(String subscriberId, GrpcClient client) {
        if (client != null && clients.remove(subscriberId, client)) {
            client.close();
        }
    }

    private record GrpcClient(ConflatedQuoteSubscription subscription,
                              StreamObserver<com.emporia.marketdata.grpc.model.ClobQuote> observer) {
        void close() {
            subscription.close();
            observer.onCompleted();
        }
    }
}

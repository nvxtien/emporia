package com.emporia.marketdata;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.marketdata.MarketDataService.DepthLevel;
import com.emporia.marketdata.MarketDataService.Quote;
import com.ettech.fixmarketsimulator.marketdataserver.api.FixSimMarketDataServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.fixprotocol.components.Fix;
import org.fixprotocol.components.Instrument;
import org.fixprotocol.components.InstrmtMDReqGrp;
import org.fixprotocol.components.MarketData;
import org.fixprotocol.components.Parties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.agrona.collections.Long2ObjectHashMap;

@Service
@ConditionalOnProperty(name = "emporia.market-data.provider", havingValue = "fix-simulator")
public class FixSimulatorMarketDataProvider implements MarketDataProvider, SmartLifecycle, HealthIndicator {
    private static final Logger log = LoggerFactory.getLogger(FixSimulatorMarketDataProvider.class);

    private final String clientId;
    private final String configuredConnections;
    private final Duration initialDataTimeout;
    private final Duration reconnectDelay;
    private final Map<String, List<FixConnection>> connectionsByMic = new TreeMap<>();
    private final Long2ObjectHashMap<BookState> books = new Long2ObjectHashMap<>();
    private final Long2ObjectHashMap<FixConnection> listingConnections = new Long2ObjectHashMap<>();
    private final ScheduledExecutorService reconnects = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "fix-simulator-reconnect");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();

    FixSimulatorMarketDataProvider(
            @Value("${emporia.market-data.fix-simulator.client-id}") String clientId,
            @Value("${emporia.market-data.fix-simulator.connections}") String configuredConnections,
            @Value("${emporia.market-data.fix-simulator.initial-data-timeout:5s}") Duration initialDataTimeout,
            @Value("${emporia.market-data.fix-simulator.reconnect-delay:5s}") Duration reconnectDelay
    ) {
        this.clientId = clientId;
        this.configuredConnections = configuredConnections;
        this.initialDataTimeout = initialDataTimeout;
        this.reconnectDelay = reconnectDelay;
    }

    @Override
    public List<Quote> quotes(List<ListingSnapshot> listings, Instant timestamp) {
        if (listings.isEmpty()) {
            return List.of();
        }
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        List<BookState> requestedBooks = new ArrayList<>(listings.size());
        for (ListingSnapshot listing : listings) {
            BookState state = books.computeIfAbsent(listing.id(), ignored -> new BookState(listing));
            requestedBooks.add(state);
            connectionFor(listing).subscribe(listing);
            if (!state.ready().isDone()) {
                pending.add(state.ready());
            }
        }
        awaitInitialBooks(pending);
        return requestedBooks.stream().map(BookState::snapshot).toList();
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Map<String, List<String>> addresses = parseConnections(configuredConnections);
        if (addresses.isEmpty()) {
            running.set(false);
            throw new IllegalStateException(
                    "FIX_SIMULATOR_CONNECTIONS must map an exchange MIC to at least one gRPC address");
        }
        addresses.forEach((mic, targets) -> connectionsByMic.put(mic,
                targets.stream().map(target -> new FixConnection(mic, target)).toList()));
        connectionsByMic.values().stream().flatMap(Collection::stream).forEach(FixConnection::connect);
    }

    @Override
    public void stop() {
        running.set(false);
        connectionsByMic.values().stream().flatMap(Collection::stream).forEach(FixConnection::close);
        reconnects.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    @Override
    public Health health() {
        long connected = connectionsByMic.values().stream().flatMap(Collection::stream)
                .filter(FixConnection::connected).count();
        long total = connectionsByMic.values().stream().mapToLong(List::size).sum();
        return (running.get() && total > 0 && connected == total ? Health.up() : Health.down())
                .withDetail("provider", "fix-simulator")
                .withDetail("connectedSources", connected)
                .withDetail("configuredSources", total)
                .withDetail("activeListings", books.size())
                .build();
    }

    private FixConnection connectionFor(ListingSnapshot listing) {
        List<FixConnection> connections = connectionsByMic.get(normalize(listing.exchangeMic()));
        if (connections == null || connections.isEmpty()) {
            throw unavailable("No FIX simulator source is configured for " + listing.exchangeMic());
        }
        int ordinal = Math.floorMod(Long.hashCode(listing.id()), connections.size());
        FixConnection selected = connections.get(ordinal);
        listingConnections.put(listing.id(), selected);
        return selected;
    }

    private void awaitInitialBooks(List<CompletableFuture<Void>> pending) {
        if (pending.isEmpty()) {
            return;
        }
        try {
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                    .get(initialDataTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable("Interrupted while waiting for FIX simulator market data", interrupted);
        } catch (Exception error) {
            throw unavailable("Timed out waiting for FIX simulator market data", error);
        }
    }

    private void handle(FixConnection connection, MarketData.MarketDataIncrementalRefresh refresh) {
        Instant timestamp = Instant.now();
        for (MarketData.MDIncGrp update : refresh.getMdIncGrpList()) {
            String symbol = update.getInstrument().getSymbol();
            connection.listings(symbol).forEach(listing -> {
                BookState state = books.computeIfAbsent(listing.id(), ignored -> new BookState(listing));
                if (state.apply(update, timestamp)) {
                    state.ready().complete(null);
                }
            });
        }
    }

    private void disconnected(FixConnection connection, Throwable error) {
        listingConnections.entrySet().stream()
                .filter(entry -> entry.getValue() == connection) // NOPMD - identity selects the exact live connection.
                .map(Map.Entry::getKey)
                .map(books::get)
                .filter(java.util.Objects::nonNull)
                .forEach(book -> book.interrupted("FIX simulator stream interrupted: " + error.getMessage()));
        if (running.get()) {
            reconnects.schedule(connection::connect, reconnectDelay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static Map<String, List<String>> parseConnections(String configured) {
        if (configured == null || configured.isBlank()) {
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String mapping : configured.split(",")) {
            String[] pair = mapping.strip().split("=", 2);
            if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()) {
                throw new IllegalStateException("Invalid FIX simulator connection mapping: " + mapping);
            }
            List<String> addresses = List.of(pair[1].split("\\|")).stream()
                    .map(String::strip).filter(value -> !value.isBlank()).toList();
            result.put(normalize(pair[0]), addresses);
        }
        return Map.copyOf(result);
    }

    private static String normalize(String value) {
        return value.strip().toUpperCase(Locale.ROOT);
    }

    private static ResponseStatusException unavailable(String detail) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
    }

    private static ResponseStatusException unavailable(String detail, Throwable cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail, cause);
    }

    private final class FixConnection {
        private final String mic;
        private final String target;
        private final Map<String, Map<Long, ListingSnapshot>> listingsBySymbol = new ConcurrentHashMap<>();
        private final AtomicReference<StreamObserver<MarketData.MarketDataRequest>> requests = new AtomicReference<>();
        private final AtomicBoolean connecting = new AtomicBoolean();
        private final AtomicBoolean connected = new AtomicBoolean();
        private volatile ManagedChannel channel;

        private FixConnection(String mic, String target) {
            this.mic = mic;
            this.target = target;
        }

        private void connect() {
            if (!running.get() || !connecting.compareAndSet(false, true)) {
                return;
            }
            closeChannel();
            channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
            Metadata headers = new Metadata();
            headers.put(Metadata.Key.of("subscriber_id", Metadata.ASCII_STRING_MARSHALLER), clientId);
            FixSimMarketDataServiceGrpc.FixSimMarketDataServiceStub stub =
                    FixSimMarketDataServiceGrpc.newStub(channel)
                            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
            StreamObserver<MarketData.MarketDataRequest> outbound = stub.connect(new StreamObserver<>() {
                @Override
                public void onNext(MarketData.MarketDataIncrementalRefresh refresh) {
                    connected.set(true);
                    handle(FixConnection.this, refresh);
                }

                @Override
                public void onError(Throwable error) {
                    connected.set(false);
                    requests.set(null);
                    connecting.set(false);
                    log.warn("FIX simulator source {} for {} disconnected: {}", target, mic, error.getMessage());
                    disconnected(FixConnection.this, error);
                }

                @Override
                public void onCompleted() {
                    onError(new IllegalStateException("remote stream completed"));
                }
            });
            requests.set(outbound);
            connecting.set(false);
            subscriptions().forEach(this::sendSubscription);
            log.info("Connected FIX simulator source {} for {}", target, mic);
        }

        private void subscribe(ListingSnapshot listing) {
            Map<Long, ListingSnapshot> symbolListings = listingsBySymbol.computeIfAbsent(
                    listing.marketSymbol(), ignored -> new ConcurrentHashMap<>());
            if (symbolListings.putIfAbsent(listing.id(), listing) == null && symbolListings.size() == 1) {
                sendSubscription(listing.marketSymbol());
            }
        }

        private Set<String> subscriptions() {
            return Set.copyOf(listingsBySymbol.keySet());
        }

        private Collection<ListingSnapshot> listings(String symbol) {
            return listingsBySymbol.getOrDefault(symbol, Map.of()).values();
        }

        private void sendSubscription(String symbol) {
            StreamObserver<MarketData.MarketDataRequest> outbound = requests.get();
            if (outbound == null) {
                return;
            }
            MarketData.MarketDataRequest request = MarketData.MarketDataRequest.newBuilder()
                    .addParties(Parties.newBuilder().setPartyId(clientId))
                    .addInstrmtMdReqGrp(InstrmtMDReqGrp.newBuilder()
                            .setInstrument(Instrument.newBuilder().setSymbol(symbol)))
                    .build();
            synchronized (outbound) {
                outbound.onNext(request);
            }
        }

        private boolean connected() {
            return connected.get();
        }

        private void close() {
            StreamObserver<MarketData.MarketDataRequest> outbound = requests.getAndSet(null);
            if (outbound != null) {
                synchronized (outbound) {
                    outbound.onCompleted();
                }
            }
            closeChannel();
            connected.set(false);
            connecting.set(false);
        }

        private void closeChannel() {
            ManagedChannel current = channel;
            channel = null;
            if (current != null) {
                current.shutdownNow();
            }
        }
    }

    private static final class BookState {
        private final ListingSnapshot listing;
        private final Map<String, DepthLevel> bids = new ConcurrentHashMap<>();
        private final Map<String, DepthLevel> offers = new ConcurrentHashMap<>();
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private BigDecimal lastPrice;
        private BigDecimal lastQuantity = BigDecimal.ZERO;
        private BigDecimal tradedVolume = BigDecimal.ZERO;
        private Instant asOf = Instant.EPOCH;
        private boolean streamInterrupted;
        private String streamStatusMessage = "";

        private BookState(ListingSnapshot listing) {
            this.listing = listing;
            this.lastPrice = listing.referencePrice();
        }

        private synchronized boolean apply(MarketData.MDIncGrp update, Instant timestamp) {
            boolean handled = switch (update.getMdEntryType()) {
                case MD_ENTRY_TYPE_BID -> updateDepth(bids, update);
                case MD_ENTRY_TYPE_OFFER -> updateDepth(offers, update);
                case MD_ENTRY_TYPE_TRADE -> {
                    lastPrice = decimal(update.getMdEntryPx());
                    lastQuantity = decimal(update.getMdEntrySize());
                    yield true;
                }
                case MD_ENTRY_TYPE_TRADE_VOLUME -> {
                    tradedVolume = decimal(update.getMdEntrySize());
                    yield true;
                }
                default -> false;
            };
            if (handled) {
                asOf = timestamp;
                streamInterrupted = false;
                streamStatusMessage = "";
            }
            return handled;
        }

        private boolean updateDepth(Map<String, DepthLevel> side, MarketData.MDIncGrp update) {
            String entryId = update.getMdEntryId().isBlank()
                    ? update.getMdEntryType().name() + ":" + decimal(update.getMdEntryPx())
                    : update.getMdEntryId();
            return switch (update.getMdUpdateAction()) {
                case MD_UPDATE_ACTION_NEW, MD_UPDATE_ACTION_CHANGE, MD_UPDATE_ACTION_OVERLAY -> {
                    side.put(entryId, new DepthLevel(decimal(update.getMdEntryPx()),
                            decimal(update.getMdEntrySize()), listing.exchangeMic(), entryId, listing.id()));
                    yield true;
                }
                case MD_UPDATE_ACTION_DELETE -> {
                    side.remove(entryId);
                    yield true;
                }
                default -> false;
            };
        }

        private synchronized Quote snapshot() {
            BigDecimal change = lastPrice.subtract(listing.previousClose());
            BigDecimal percent = listing.previousClose().signum() == 0 ? BigDecimal.ZERO
                    : change.divide(listing.previousClose(), 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
            Comparator<DepthLevel> byPrice = Comparator.comparing(DepthLevel::price);
            return new Quote(listing.id(), listing.symbol(), listing.currency(), lastPrice, lastQuantity,
                    listing.previousClose(), change, percent, tradedVolume,
                    bids.values().stream().sorted(byPrice.reversed()).toList(),
                    offers.values().stream().sorted(byPrice).toList(),
                    asOf.equals(Instant.EPOCH) ? Instant.now() : asOf, "FIX_SIMULATOR",
                    streamInterrupted, streamStatusMessage);
        }

        private synchronized void interrupted(String message) {
            streamInterrupted = true;
            streamStatusMessage = message;
            asOf = Instant.now();
            ready.complete(null);
        }

        private CompletableFuture<Void> ready() {
            return ready;
        }

        private static BigDecimal decimal(Fix.Decimal64 value) {
            return BigDecimal.valueOf(value.getMantissa()).scaleByPowerOfTen(value.getExponent());
        }
    }
}

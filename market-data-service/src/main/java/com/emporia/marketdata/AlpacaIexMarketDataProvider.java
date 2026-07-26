package com.emporia.marketdata;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.marketdata.MarketDataService.DepthLevel;
import com.emporia.marketdata.MarketDataService.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
@ConditionalOnProperty(name = "emporia.market-data.provider", havingValue = "alpaca-iex")
public class AlpacaIexMarketDataProvider implements MarketDataProvider, SmartLifecycle, HealthIndicator {
    private static final Logger log = LoggerFactory.getLogger(AlpacaIexMarketDataProvider.class);
    private static final BigDecimal ROUND_LOT_SIZE = BigDecimal.valueOf(100);

    private final ObjectMapper objectMapper;
    private final URI websocketUrl;
    private final URI snapshotsUrl;
    private final String apiKey;
    private final String apiSecret;
    private final Duration initialDataTimeout;
    private final Duration reconnectDelay;
    private final int maximumSymbols;
    private final HttpClient httpClient;
    private final ScheduledExecutorService reconnectExecutor;
    private final ConcurrentMap<String, LiveMarketState> marketStates = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<Void>> quoteReady = new ConcurrentHashMap<>();
    private final AtomicReference<WebSocket> webSocket = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final AtomicBoolean authenticated = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicReference<String> connectionState = new AtomicReference<>("not connected");
    private final Object subscriptionLock = new Object();
    private final LinkedHashMap<String, Boolean> desiredSymbols = new LinkedHashMap<>(32, 0.75f, true);
    private final Set<String> subscribedSymbols = new HashSet<>();

    AlpacaIexMarketDataProvider(
            ObjectMapper objectMapper,
            @Value("${emporia.market-data.alpaca.websocket-url}") URI websocketUrl,
            @Value("${emporia.market-data.alpaca.snapshots-url}") URI snapshotsUrl,
            @Value("${emporia.market-data.alpaca.api-key:}") String apiKey,
            @Value("${emporia.market-data.alpaca.api-secret:}") String apiSecret,
            @Value("${emporia.market-data.alpaca.initial-data-timeout:5s}") Duration initialDataTimeout,
            @Value("${emporia.market-data.alpaca.reconnect-delay:5s}") Duration reconnectDelay,
            @Value("${emporia.market-data.alpaca.maximum-symbols:30}") int maximumSymbols
    ) {
        this.objectMapper = objectMapper;
        this.websocketUrl = websocketUrl;
        this.snapshotsUrl = snapshotsUrl;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.initialDataTimeout = initialDataTimeout;
        this.reconnectDelay = reconnectDelay;
        this.maximumSymbols = maximumSymbols;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.reconnectExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "alpaca-iex-reconnect");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public List<Quote> quotes(List<ListingSnapshot> listings, Instant timestamp) {
        if (listings.isEmpty()) {
            return List.of();
        }

        Set<String> symbols = new TreeSet<>();
        for (ListingSnapshot listing : listings) {
            symbols.add(alpacaSymbol(listing));
        }
        ensureSubscriptions(symbols);
        loadInitialSnapshots(symbols);
        awaitInitialQuotes(symbols);
        return listings.stream().map(this::currentQuote).toList();
    }

    Quote currentQuote(ListingSnapshot listing) {
        String symbol = alpacaSymbol(listing);
        LiveMarketState state = marketStates.get(symbol);
        LiveSnapshot live = state == null ? null : state.snapshot();
        if (live == null || live.quote() == null) {
            throw unavailable("No Alpaca IEX quote is available for " + symbol);
        }

        QuoteUpdate quote = live.quote();
        TradeUpdate trade = live.trade();
        BigDecimal lastPrice = trade == null ? quotePrice(quote) : trade.price();
        BigDecimal lastQuantity = trade == null ? BigDecimal.ZERO : BigDecimal.valueOf(trade.size());
        Instant asOf = trade == null || quote.timestamp().isAfter(trade.timestamp())
                ? quote.timestamp()
                : trade.timestamp();
        BigDecimal change = lastPrice.subtract(listing.previousClose());
        BigDecimal changePercent = listing.previousClose().signum() == 0
                ? BigDecimal.ZERO
                : change.divide(listing.previousClose(), 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);

        List<DepthLevel> bids = quote.bidPrice().signum() == 0
                ? List.of()
                : List.of(new DepthLevel(quote.bidPrice(), BigDecimal.valueOf(quote.bidRoundLots())
                        .multiply(ROUND_LOT_SIZE), exchangeMic(quote.bidExchange())));
        List<DepthLevel> offers = quote.askPrice().signum() == 0
                ? List.of()
                : List.of(new DepthLevel(quote.askPrice(), BigDecimal.valueOf(quote.askRoundLots())
                        .multiply(ROUND_LOT_SIZE), exchangeMic(quote.askExchange())));

        return new Quote(listing.id(), listing.symbol(), listing.currency(), lastPrice, lastQuantity,
                listing.previousClose(), change, changePercent, BigDecimal.valueOf(live.observedVolume()), bids, offers,
                asOf, "ALPACA_IEX");
    }

    @Override
    public void start() {
        requireSetting(apiKey, "APCA_API_KEY_ID");
        requireSetting(apiSecret, "APCA_API_SECRET_KEY");
        if (maximumSymbols < 1) {
            throw new IllegalStateException("ALPACA_MAX_SYMBOLS must be greater than zero");
        }
        if (running.compareAndSet(false, true)) {
            connect();
        }
    }

    @Override
    public void stop() {
        running.set(false);
        authenticated.set(false);
        WebSocket socket = webSocket.getAndSet(null);
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "service stopping");
        }
        reconnectExecutor.shutdownNow();
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
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
        int activeSymbols;
        synchronized (subscriptionLock) {
            activeSymbols = subscribedSymbols.size();
        }
        if (authenticated.get()) {
            return Health.up()
                    .withDetail("provider", "alpaca-iex")
                    .withDetail("activeSymbols", activeSymbols)
                    .build();
        }
        return Health.down()
                .withDetail("provider", "alpaca-iex")
                .withDetail("connection", connectionState.get())
                .build();
    }

    void handlePayload(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (!root.isArray()) {
                log.warn("Ignoring non-array Alpaca IEX message");
                return;
            }
            for (JsonNode message : root) {
                handleMessage(message);
            }
        } catch (RuntimeException exception) {
            log.warn("Unable to parse Alpaca IEX message: {}", exception.getMessage());
        }
    }

    private void handleMessage(JsonNode message) {
        String type = message.path("T").asText();
        switch (type) {
            case "success" -> handleSuccess(message.path("msg").asText());
            case "subscription" -> updateSubscriptions(message);
            case "error" -> handleStreamError(message);
            case "q" -> handleQuote(message);
            case "t" -> handleTrade(message);
            default -> {
                // Other Alpaca channels are intentionally not subscribed.
            }
        }
    }

    private void handleSuccess(String message) {
        if ("authenticated".equals(message)) {
            authenticated.set(true);
            connectionState.set("authenticated");
            log.info("Authenticated with the Alpaca IEX market-data stream");
            subscribeToDesiredSymbols();
        }
    }

    private void handleStreamError(JsonNode message) {
        int code = message.path("code").asInt();
        String detail = message.path("msg").asText();
        connectionState.set("error " + code + ": " + detail);
        log.error("Alpaca IEX stream error {}: {}", code, detail);
        synchronized (subscriptionLock) {
            subscribedSymbols.clear();
        }
    }

    private void handleQuote(JsonNode message) {
        String symbol = normalizedSymbol(message.path("S").asText());
        QuoteUpdate update = quoteUpdate(message);
        marketStates.computeIfAbsent(symbol, ignored -> new LiveMarketState()).updateQuote(update);
        quoteReady.computeIfAbsent(symbol, ignored -> new CompletableFuture<>()).complete(null);
    }

    private void handleTrade(JsonNode message) {
        String symbol = normalizedSymbol(message.path("S").asText());
        TradeUpdate update = tradeUpdate(message);
        marketStates.computeIfAbsent(symbol, ignored -> new LiveMarketState()).updateTrade(update);
    }

    private void ensureSubscriptions(Set<String> symbols) {
        if (symbols.size() > maximumSymbols) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Alpaca IEX supports at most " + maximumSymbols + " active symbols in this configuration");
        }

        Set<String> evicted = new TreeSet<>();
        Set<String> toSubscribe = Set.of();
        Set<String> toUnsubscribe = Set.of();
        synchronized (subscriptionLock) {
            for (String symbol : symbols) {
                desiredSymbols.put(normalizedSymbol(symbol), Boolean.TRUE);
            }

            Iterator<String> iterator = desiredSymbols.keySet().iterator();
            while (desiredSymbols.size() > maximumSymbols && iterator.hasNext()) {
                String candidate = iterator.next();
                if (!symbols.contains(candidate)) {
                    iterator.remove();
                    evicted.add(candidate);
                }
            }

            if (authenticated.get()) {
                toUnsubscribe = evicted.stream().filter(subscribedSymbols::remove)
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
                toSubscribe = desiredSymbols.keySet().stream().filter(symbol -> !subscribedSymbols.contains(symbol))
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
                subscribedSymbols.addAll(toSubscribe);
            }
        }

        evicted.forEach(symbol -> {
            marketStates.remove(symbol);
            quoteReady.remove(symbol);
        });
        sendSubscription("unsubscribe", toUnsubscribe);
        sendSubscription("subscribe", toSubscribe);
    }

    private void subscribeToDesiredSymbols() {
        Set<String> symbols;
        synchronized (subscriptionLock) {
            subscribedSymbols.clear();
            symbols = new TreeSet<>(desiredSymbols.keySet());
            subscribedSymbols.addAll(symbols);
        }
        sendSubscription("subscribe", symbols);
    }

    private void updateSubscriptions(JsonNode message) {
        Set<String> serverSymbols = new HashSet<>();
        addSymbols(message.path("trades"), serverSymbols);
        addSymbols(message.path("quotes"), serverSymbols);
        synchronized (subscriptionLock) {
            subscribedSymbols.clear();
            subscribedSymbols.addAll(serverSymbols);
        }
        log.info("Alpaca IEX subscriptions active for {} symbol(s)", serverSymbols.size());
    }

    private void sendSubscription(String action, Collection<String> symbols) {
        if (symbols.isEmpty()) {
            return;
        }
        sendJson(Map.of("action", action, "trades", symbols, "quotes", symbols));
    }

    private void awaitInitialQuotes(Set<String> symbols) {
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (String symbol : symbols) {
            LiveMarketState state = marketStates.get(symbol);
            if (state == null || state.snapshot().quote() == null) {
                pending.add(quoteReady.computeIfAbsent(symbol, ignored -> new CompletableFuture<>()));
            }
        }
        if (pending.isEmpty()) {
            return;
        }

        try {
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                    .get(initialDataTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            List<String> missing = symbols.stream()
                    .filter(symbol -> {
                        LiveMarketState state = marketStates.get(symbol);
                        return state == null || state.snapshot().quote() == null;
                    })
                    .sorted()
                    .toList();
            throw unavailable("Timed out waiting for Alpaca IEX quotes for " + String.join(", ", missing), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable("Interrupted while waiting for Alpaca IEX quotes", exception);
        } catch (Exception exception) {
            throw unavailable("Unable to obtain Alpaca IEX quotes", exception);
        }
    }

    private void loadInitialSnapshots(Set<String> symbols) {
        Set<String> missing = symbols.stream()
                .filter(symbol -> {
                    LiveMarketState state = marketStates.get(symbol);
                    return state == null || state.snapshot().quote() == null;
                })
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        if (missing.isEmpty()) {
            return;
        }

        String separator = snapshotsUrl.toString().contains("?") ? "&" : "?";
        String encodedSymbols = URLEncoder.encode(String.join(",", missing), StandardCharsets.UTF_8);
        URI requestUri = URI.create(snapshotsUrl + separator + "symbols=" + encodedSymbols + "&feed=iex");
        HttpRequest request = HttpRequest.newBuilder(requestUri)
                .timeout(initialDataTimeout)
                .header("Accept", "application/json")
                .header("APCA-API-KEY-ID", apiKey)
                .header("APCA-API-SECRET-KEY", apiSecret)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Alpaca IEX snapshot request returned HTTP {}", response.statusCode());
                return;
            }
            seedSnapshots(objectMapper.readTree(response.body()), missing);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while loading Alpaca IEX snapshots");
        } catch (Exception exception) {
            log.warn("Unable to load Alpaca IEX snapshots: {}", exception.getMessage());
        }
    }

    private void seedSnapshots(JsonNode snapshots, Set<String> requestedSymbols) {
        for (String symbol : requestedSymbols) {
            JsonNode snapshot = snapshots.path(symbol);
            JsonNode latestQuote = snapshot.path("latestQuote");
            if (!latestQuote.isObject()) {
                continue;
            }

            try {
                QuoteUpdate quote = quoteUpdate(latestQuote);
                JsonNode latestTrade = snapshot.path("latestTrade");
                TradeUpdate trade = latestTrade.isObject() ? tradeUpdate(latestTrade) : null;
                marketStates.computeIfAbsent(symbol, ignored -> new LiveMarketState()).seed(quote, trade);
                quoteReady.computeIfAbsent(symbol, ignored -> new CompletableFuture<>()).complete(null);
            } catch (RuntimeException exception) {
                log.warn("Ignoring invalid Alpaca IEX snapshot for {}: {}", symbol, exception.getMessage());
            }
        }
    }

    private void connect() {
        if (!running.get() || !connecting.compareAndSet(false, true)) {
            return;
        }
        authenticated.set(false);
        connectionState.set("connecting");
        log.info("Connecting to Alpaca IEX market-data stream at {}", websocketUrl);
        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(websocketUrl, webSocketListener())
                .whenComplete((socket, failure) -> {
                    connecting.set(false);
                    if (failure != null) {
                        connectionState.set("connect failed: " + failure.getMessage());
                        log.warn("Unable to connect to Alpaca IEX: {}", failure.getMessage());
                        scheduleReconnect();
                    }
                });
    }

    WebSocket.Listener webSocketListener() {
        return new AlpacaWebSocketListener();
    }

    private void sendAuthentication() {
        sendJson(Map.of("action", "auth", "key", apiKey, "secret", apiSecret));
    }

    private void sendJson(Map<String, ?> message) {
        WebSocket socket = webSocket.get();
        if (socket == null) {
            return;
        }
        try {
            socket.sendText(objectMapper.writeValueAsString(message), true)
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            log.warn("Unable to send Alpaca IEX message: {}", failure.getMessage());
                        }
                    });
        } catch (RuntimeException exception) {
            log.warn("Unable to encode Alpaca IEX message: {}", exception.getMessage());
        }
    }

    private void disconnected(WebSocket socket, String reason) {
        if (!webSocket.compareAndSet(socket, null)) {
            return;
        }
        authenticated.set(false);
        connectionState.set(reason);
        synchronized (subscriptionLock) {
            subscribedSymbols.clear();
        }
        if (running.get()) {
            log.warn("Alpaca IEX stream disconnected: {}", reason);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running.get() || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        reconnectExecutor.schedule(() -> {
            reconnectScheduled.set(false);
            connect();
        }, reconnectDelay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static BigDecimal decimal(JsonNode message, String field) {
        return new BigDecimal(message.path(field).asText());
    }

    private static QuoteUpdate quoteUpdate(JsonNode message) {
        return new QuoteUpdate(
                decimal(message, "bp"),
                message.path("bs").asLong(),
                message.path("bx").asText(),
                decimal(message, "ap"),
                message.path("as").asLong(),
                message.path("ax").asText(),
                timestamp(message)
        );
    }

    private static TradeUpdate tradeUpdate(JsonNode message) {
        return new TradeUpdate(decimal(message, "p"), message.path("s").asLong(), timestamp(message));
    }

    private static BigDecimal quotePrice(QuoteUpdate quote) {
        boolean hasBid = quote.bidPrice().signum() > 0;
        boolean hasAsk = quote.askPrice().signum() > 0;
        if (hasBid && hasAsk) {
            return quote.bidPrice().add(quote.askPrice()).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
        }
        if (hasBid) {
            return quote.bidPrice();
        }
        if (hasAsk) {
            return quote.askPrice();
        }
        throw unavailable("Alpaca IEX quote has neither a bid nor an offer");
    }

    private static Instant timestamp(JsonNode message) {
        return Instant.parse(message.path("t").asText());
    }

    private static String alpacaSymbol(ListingSnapshot listing) {
        String symbol = StringUtils.hasText(listing.marketSymbol()) ? listing.marketSymbol() : listing.symbol();
        return normalizedSymbol(symbol);
    }

    private static String normalizedSymbol(String symbol) {
        return symbol.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String exchangeMic(String exchangeCode) {
        return "V".equals(exchangeCode) ? "IEXG" : exchangeCode;
    }

    private static void addSymbols(JsonNode values, Set<String> target) {
        if (values.isArray()) {
            values.forEach(value -> target.add(normalizedSymbol(value.asText())));
        }
    }

    private static void requireSetting(String value, String environmentVariable) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(environmentVariable + " must be set when MARKET_DATA_PROVIDER=alpaca-iex");
        }
    }

    private static ResponseStatusException unavailable(String detail) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
    }

    private static ResponseStatusException unavailable(String detail, Throwable cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail, cause);
    }

    private final class AlpacaWebSocketListener implements WebSocket.Listener {
        private final StringBuilder payload = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            if (!running.get()) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "provider stopped");
                return;
            }
            webSocket.set(socket);
            socket.request(1);
            sendAuthentication();
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            payload.append(data);
            if (last) {
                String completePayload = payload.toString();
                payload.setLength(0);
                handlePayload(completePayload);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            disconnected(socket, "close " + statusCode + " " + reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            disconnected(socket, error.getMessage());
        }
    }

    private static final class LiveMarketState {
        private QuoteUpdate quote;
        private TradeUpdate trade;
        private long observedVolume;

        synchronized void updateQuote(QuoteUpdate update) {
            quote = update;
        }

        synchronized void updateTrade(TradeUpdate update) {
            trade = update;
            observedVolume += update.size();
        }

        synchronized void seed(QuoteUpdate quoteUpdate, TradeUpdate tradeUpdate) {
            if (quote == null || quote.timestamp().isBefore(quoteUpdate.timestamp())) {
                quote = quoteUpdate;
            }
            if (tradeUpdate != null && (trade == null || trade.timestamp().isBefore(tradeUpdate.timestamp()))) {
                trade = tradeUpdate;
            }
        }

        synchronized LiveSnapshot snapshot() {
            return new LiveSnapshot(quote, trade, observedVolume);
        }
    }

    private record QuoteUpdate(
            BigDecimal bidPrice,
            long bidRoundLots,
            String bidExchange,
            BigDecimal askPrice,
            long askRoundLots,
            String askExchange,
            Instant timestamp
    ) { }

    private record TradeUpdate(BigDecimal price, long size, Instant timestamp) { }

    private record LiveSnapshot(QuoteUpdate quote, TradeUpdate trade, long observedVolume) { }
}

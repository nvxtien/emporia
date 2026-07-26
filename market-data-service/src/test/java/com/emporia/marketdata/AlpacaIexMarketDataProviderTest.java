package com.emporia.marketdata;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.getField;
import static org.springframework.test.util.ReflectionTestUtils.invokeMethod;
import static org.springframework.test.util.ReflectionTestUtils.setField;

class AlpacaIexMarketDataProviderTest {
    private final AlpacaIexMarketDataProvider provider = new AlpacaIexMarketDataProvider(
            new ObjectMapper(),
            URI.create("wss://stream.data.alpaca.markets/v2/test"),
            URI.create("https://data.alpaca.markets/v2/stocks/snapshots"),
            "test-key",
            "test-secret",
            Duration.ofSeconds(1),
            Duration.ofHours(1),
            30
    );

    @AfterEach
    void closeProvider() {
        provider.stop();
    }

    @Test
    void convertsAlpacaTradeAndTopOfBookMessages() {
        provider.handlePayload("""
                [
                  {"T":"q","S":"AAPL","bx":"V","bp":199.10,"bs":2,"ax":"V","ap":199.20,"as":3,
                   "t":"2026-07-23T14:30:00.100Z"},
                  {"T":"t","S":"AAPL","x":"V","p":199.15,"s":25,"t":"2026-07-23T14:30:00.200Z"}
                ]
                """);

        MarketDataService.Quote quote = provider.currentQuote(listing());

        assertThat(quote.source()).isEqualTo("ALPACA_IEX");
        assertThat(quote.lastPrice()).isEqualByComparingTo("199.15");
        assertThat(quote.lastQuantity()).isEqualByComparingTo("25");
        assertThat(quote.tradedVolume()).isEqualByComparingTo("25");
        assertThat(quote.bids()).singleElement().satisfies(level -> {
            assertThat(level.price()).isEqualByComparingTo("199.10");
            assertThat(level.size()).isEqualByComparingTo("200");
            assertThat(level.exchangeMic()).isEqualTo("IEXG");
        });
        assertThat(quote.offers()).singleElement().satisfies(level -> {
            assertThat(level.price()).isEqualByComparingTo("199.20");
            assertThat(level.size()).isEqualByComparingTo("300");
            assertThat(level.exchangeMic()).isEqualTo("IEXG");
        });
        assertThat(quote.asOf()).isEqualTo(Instant.parse("2026-07-23T14:30:00.200Z"));
    }

    @Test
    void usesMidpointBeforeTheFirstTradeArrives() {
        provider.handlePayload("""
                [{"T":"q","S":"AAPL","bx":"V","bp":199.10,"bs":1,"ax":"V","ap":199.20,"as":1,
                  "t":"2026-07-23T14:30:00.100Z"}]
                """);

        MarketDataService.Quote quote = provider.currentQuote(listing());

        assertThat(quote.lastPrice()).isEqualByComparingTo("199.150000");
        assertThat(quote.lastQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void supportsAOneSidedBookWithoutInventingAnOffer() {
        provider.handlePayload("""
                [{"T":"q","S":"AAPL","bx":"V","bp":199.10,"bs":2,"ax":" ","ap":0,"as":0,
                  "t":"2026-07-23T14:30:00.100Z"}]
                """);

        MarketDataService.Quote quote = provider.currentQuote(listing());

        assertThat(quote.lastPrice()).isEqualByComparingTo("199.10");
        assertThat(quote.bids()).singleElement()
                .satisfies(level -> assertThat(level.price()).isEqualByComparingTo("199.10"));
        assertThat(quote.offers()).isEmpty();
    }

    @Test
    void handlesEmptyRequestsMissingQuotesAndAskOnlyBooks() {
        assertThat(provider.quotes(List.of(), Instant.now())).isEmpty();
        assertThatThrownBy(() -> provider.currentQuote(listing()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No Alpaca IEX quote is available for AAPL");

        provider.handlePayload("""
                [{"T":"t","S":"MSFT","x":"V","p":410.10,"s":5,"t":"2026-07-23T14:30:00.100Z"}]
                """);
        assertThatThrownBy(() -> provider.currentQuote(listing(2, "MSFT")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No Alpaca IEX quote is available for MSFT");

        provider.handlePayload("""
                [{"T":"q","S":"AAPL","bx":" ","bp":0,"bs":0,"ax":"P","ap":201.20,"as":3,
                  "t":"2026-07-23T14:31:00.100Z"}]
                """);
        ListingSnapshot zeroClose = listing(3, "aapl", " ", BigDecimal.ZERO);
        MarketDataService.Quote askOnly = provider.quotes(List.of(zeroClose), Instant.now()).getFirst();

        assertThat(askOnly.lastPrice()).isEqualByComparingTo("201.20");
        assertThat(askOnly.changePercent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(askOnly.bids()).isEmpty();
        assertThat(askOnly.offers()).singleElement()
                .satisfies(level -> assertThat(level.exchangeMic()).isEqualTo("P"));

        provider.handlePayload("""
                [{"T":"q","S":"ZERO","bx":" ","bp":0,"bs":0,"ax":" ","ap":0,"as":0,
                  "t":"2026-07-23T14:32:00.100Z"}]
                """);
        assertThatThrownBy(() -> provider.currentQuote(listing(4, "ZERO")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("neither a bid nor an offer");
    }

    @Test
    void reportsStreamAuthenticationInHealth() {
        assertThat(provider.health().getStatus()).isEqualTo(Status.DOWN);

        provider.handlePayload("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");

        assertThat(provider.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void handlesStatusSubscriptionErrorAndMalformedMessages() {
        provider.handlePayload("{}");
        provider.handlePayload("{");
        provider.handlePayload("""
                [
                  {"T":"success","msg":"connected"},
                  {"T":"other","msg":"ignored"},
                  {"T":"success","msg":"authenticated"},
                  {"T":"subscription","trades":[" aapl "],"quotes":["msft"]}
                ]
                """);

        assertThat(provider.health().getStatus()).isEqualTo(Status.UP);
        assertThat(provider.health().getDetails()).containsEntry("activeSymbols", 2);

        provider.handlePayload("""
                [{"T":"subscription","trades":"AAPL","quotes":null}]
                """);
        assertThat(provider.health().getDetails()).containsEntry("activeSymbols", 0);

        provider.handlePayload("""
                [
                  {"T":"subscription","trades":["AAPL"],"quotes":["MSFT"]},
                  {"T":"error","code":406,"msg":"symbol limit exceeded"}
                ]
                """);
        assertThat(provider.health().getStatus()).isEqualTo(Status.UP);
        assertThat(provider.health().getDetails()).containsEntry("activeSymbols", 0);
        assertThat(getField(provider, "connectionState"))
                .isInstanceOfSatisfying(AtomicReference.class,
                        state -> assertThat(state.get()).isEqualTo("error 406: symbol limit exceeded"));
    }

    @Test
    void websocketListenerClosesAnOpeningSocketWhenProviderIsStopped() {
        WebSocket socket = socket();
        WebSocket.Listener listener = provider.webSocketListener();

        listener.onOpen(socket);

        verify(socket).sendClose(WebSocket.NORMAL_CLOSURE, "provider stopped");
        verify(socket, never()).request(1);
    }

    @Test
    void websocketListenerAuthenticatesAndCombinesFragmentedMessages() {
        markProviderRunning();
        WebSocket socket = socket();
        WebSocket.Listener listener = provider.webSocketListener();

        listener.onOpen(socket);
        listener.onText(socket,
                "[{\"T\":\"q\",\"S\":\"AAPL\",\"bx\":\"V\",\"bp\":199.10,\"bs\":2,",
                false);
        listener.onText(socket,
                "\"ax\":\"V\",\"ap\":199.20,\"as\":3,\"t\":\"2026-07-23T14:30:00.100Z\"}]",
                true);

        verify(socket).sendText(
                org.mockito.ArgumentMatchers.argThat(payload ->
                        payload.toString().contains("\"action\":\"auth\"")
                                && payload.toString().contains("\"key\":\"test-key\"")
                                && payload.toString().contains("\"secret\":\"test-secret\"")),
                eq(true));
        verify(socket, org.mockito.Mockito.times(3)).request(1);
        assertThat(provider.currentQuote(listing()).lastPrice()).isEqualByComparingTo("199.150000");
    }

    @Test
    void websocketListenerRecordsCloseAndErrorDisconnections() {
        markProviderRunning();
        WebSocket firstSocket = socket();
        WebSocket.Listener listener = provider.webSocketListener();
        listener.onOpen(firstSocket);

        assertThat(listener.onClose(firstSocket, 1001, "going away").toCompletableFuture()).isCompleted();
        assertThat(provider.health().getDetails()).containsEntry("connection", "close 1001 going away");

        WebSocket secondSocket = socket();
        listener.onOpen(secondSocket);
        listener.onError(secondSocket, new IllegalStateException("socket failed"));

        assertThat(provider.health().getDetails()).containsEntry("connection", "socket failed");
    }

    @Test
    void evictsOldSymbolsAndSendsSubscriptionChanges() {
        AlpacaIexMarketDataProvider limitedProvider = providerWith(
                URI.create("https://data.alpaca.markets/v2/stocks/snapshots"), Duration.ofSeconds(1), 2);
        WebSocket socket = socket();
        try {
            markProviderRunning(limitedProvider);
            limitedProvider.webSocketListener().onOpen(socket);
            invokeMethod(limitedProvider, "ensureSubscriptions",
                    new LinkedHashSet<>(List.of("AAPL", "MSFT")));
            limitedProvider.handlePayload("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");
            limitedProvider.handlePayload("""
                    [{"T":"q","S":"AAPL","bx":"V","bp":199.10,"bs":2,"ax":"V","ap":199.20,"as":3,
                      "t":"2026-07-23T14:30:00.100Z"}]
                    """);

            invokeMethod(limitedProvider, "ensureSubscriptions",
                    new LinkedHashSet<>(List.of("NVDA")));

            ArgumentCaptor<CharSequence> payloads = ArgumentCaptor.forClass(CharSequence.class);
            verify(socket, times(4)).sendText(payloads.capture(), eq(true));
            assertThat(payloads.getAllValues()).anySatisfy(payload ->
                    assertThat(payload.toString())
                            .contains("\"action\":\"unsubscribe\"", "\"AAPL\""));
            assertThat(payloads.getAllValues()).anySatisfy(payload ->
                    assertThat(payload.toString())
                            .contains("\"action\":\"subscribe\"", "\"NVDA\""));
            assertThatThrownBy(() -> limitedProvider.currentQuote(listing()))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("No Alpaca IEX quote is available for AAPL");
        } finally {
            limitedProvider.stop();
        }
    }

    @Test
    void validatesLifecycleConfigurationAndRunsStopCallback() {
        AlpacaIexMarketDataProvider missingSecret = new AlpacaIexMarketDataProvider(
                new ObjectMapper(),
                URI.create("wss://stream.data.alpaca.markets/v2/test"),
                URI.create("https://data.alpaca.markets/v2/stocks/snapshots"),
                "test-key",
                "",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                30
        );
        AlpacaIexMarketDataProvider invalidLimit = providerWith(
                URI.create("https://data.alpaca.markets/v2/stocks/snapshots"), Duration.ofSeconds(1), 0);
        try {
            assertThatThrownBy(missingSecret::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("APCA_API_SECRET_KEY");
            assertThatThrownBy(invalidLimit::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ALPACA_MAX_SYMBOLS");

            invokeMethod(provider, "connect");
            invokeMethod(provider, "scheduleReconnect");
            assertThat(provider.isRunning()).isFalse();
            assertThat(provider.getPhase()).isEqualTo(Integer.MAX_VALUE - 100);

            markProviderRunning();
            WebSocket socket = socket();
            provider.webSocketListener().onOpen(socket);
            AtomicBoolean callbackCalled = new AtomicBoolean();
            provider.stop(() -> callbackCalled.set(true));

            assertThat(provider.isRunning()).isFalse();
            assertThat(callbackCalled).isTrue();
            verify(socket).sendClose(WebSocket.NORMAL_CLOSURE, "service stopping");
        } finally {
            missingSecret.stop();
            invalidLimit.stop();
        }
    }

    @Test
    void startsOnceAndReportsAsynchronousConnectionFailure() {
        HttpClient httpClient = mock(HttpClient.class);
        WebSocket.Builder builder = mock(WebSocket.Builder.class);
        CompletableFuture<WebSocket> connection = new CompletableFuture<>();
        when(httpClient.newWebSocketBuilder()).thenReturn(builder);
        when(builder.connectTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.buildAsync(any(URI.class), any(WebSocket.Listener.class))).thenReturn(connection);

        AlpacaIexMarketDataProvider connectingProvider = providerWith(
                URI.create("https://data.alpaca.markets/v2/stocks/snapshots"), Duration.ofSeconds(1), 30);
        try {
            setField(connectingProvider, "httpClient", httpClient);
            connectingProvider.start();
            connectingProvider.start();
            invokeMethod(connectingProvider, "connect");

            assertThat(connectingProvider.isRunning()).isTrue();
            assertThat(connectingProvider.health().getDetails()).containsEntry("connection", "connecting");
            verify(builder).buildAsync(any(URI.class), any(WebSocket.Listener.class));

            connection.completeExceptionally(new IllegalStateException("offline"));

            assertThat(connectingProvider.health().getDetails())
                    .containsEntry("connection", "connect failed: offline");
            assertThat(getField(connectingProvider, "reconnectScheduled"))
                    .isInstanceOfSatisfying(AtomicBoolean.class, scheduled -> assertThat(scheduled).isTrue());
        } finally {
            connectingProvider.stop();
        }
    }

    @Test
    void handlesSuccessfulConnectionCompletionAndSendFailures() {
        HttpClient httpClient = mock(HttpClient.class);
        WebSocket.Builder builder = mock(WebSocket.Builder.class);
        WebSocket connectedSocket = socket();
        when(httpClient.newWebSocketBuilder()).thenReturn(builder);
        when(builder.connectTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.buildAsync(any(URI.class), any(WebSocket.Listener.class)))
                .thenReturn(CompletableFuture.completedFuture(connectedSocket));

        AlpacaIexMarketDataProvider connectedProvider = providerWith(
                URI.create("https://data.alpaca.markets/v2/stocks/snapshots"), Duration.ofSeconds(1), 30);
        try {
            setField(connectedProvider, "httpClient", httpClient);
            connectedProvider.start();
            assertThat(getField(connectedProvider, "connecting"))
                    .isInstanceOfSatisfying(AtomicBoolean.class, connecting -> assertThat(connecting).isFalse());
        } finally {
            connectedProvider.stop();
        }

        invokeMethod(provider, "sendJson", Map.of("action", "ignored-without-socket"));
        invokeMethod(provider, "disconnected", socket(), "stale socket");

        markProviderRunning();
        WebSocket failingSocket = socket();
        when(failingSocket.sendText(anyString(), eq(true)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("send failed")));
        provider.webSocketListener().onOpen(failingSocket);

        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new IllegalArgumentException("encode failed"));
        AlpacaIexMarketDataProvider encodingProvider = new AlpacaIexMarketDataProvider(
                failingMapper,
                URI.create("wss://stream.data.alpaca.markets/v2/test"),
                URI.create("https://data.alpaca.markets/v2/stocks/snapshots"),
                "test-key",
                "test-secret",
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                30
        );
        try {
            markProviderRunning(encodingProvider);
            encodingProvider.webSocketListener().onOpen(socket());
        } finally {
            encodingProvider.stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsInterruptedAndFailedInitialQuoteWaits() {
        ConcurrentMap<String, CompletableFuture<Void>> quoteReady =
                (ConcurrentMap<String, CompletableFuture<Void>>) getField(provider, "quoteReady");
        quoteReady.put("READY", CompletableFuture.completedFuture(null));
        invokeMethod(provider, "awaitInitialQuotes", Set.of("READY"));

        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> invokeMethod(provider, "awaitInitialQuotes", Set.of("AAPL")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Interrupted while waiting");
        } finally {
            assertThat(Thread.interrupted()).isTrue();
        }

        CompletableFuture<Void> failedQuote = new CompletableFuture<>();
        failedQuote.completeExceptionally(new IllegalStateException("failed quote"));
        quoteReady.put("AAPL", failedQuote);

        assertThatThrownBy(() -> invokeMethod(provider, "awaitInitialQuotes", Set.of("AAPL")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unable to obtain Alpaca IEX quotes");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void handlesSnapshotTransportInterruptionsAndFailures() throws Exception {
        AlpacaIexMarketDataProvider transportProvider = providerWith(
                URI.create("https://data.alpaca.markets/v2/stocks/snapshots"), Duration.ofSeconds(1), 30);
        HttpClient interruptedClient = mock(HttpClient.class);
        doThrow(new InterruptedException("request interrupted"))
                .when(interruptedClient)
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        try {
            setField(transportProvider, "httpClient", interruptedClient);
            invokeMethod(transportProvider, "loadInitialSnapshots", Set.of("AAPL"));
            assertThat(Thread.interrupted()).isTrue();

            HttpClient failedClient = mock(HttpClient.class);
            doThrow(new IOException("request failed"))
                    .when(failedClient)
                    .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
            setField(transportProvider, "httpClient", failedClient);
            invokeMethod(transportProvider, "loadInitialSnapshots", Set.of("AAPL"));
        } finally {
            transportProvider.stop();
        }
    }

    @Test
    void executesTheScheduledReconnectTask() {
        AlpacaIexMarketDataProvider reconnectingProvider = providerWith(
                URI.create("https://data.alpaca.markets/v2/stocks/snapshots"), Duration.ofSeconds(1), 30);
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(executor)
                .schedule(any(Runnable.class), eq(1000L), eq(TimeUnit.MILLISECONDS));
        try {
            setField(reconnectingProvider, "reconnectExecutor", executor);
            markProviderRunning(reconnectingProvider);
            assertThat(getField(reconnectingProvider, "connecting"))
                    .isInstanceOfSatisfying(AtomicBoolean.class, connecting -> connecting.set(true));

            invokeMethod(reconnectingProvider, "scheduleReconnect");

            ArgumentCaptor<Runnable> reconnectTask = ArgumentCaptor.forClass(Runnable.class);
            verify(executor).schedule(reconnectTask.capture(), eq(1000L), eq(TimeUnit.MILLISECONDS));
            reconnectTask.getValue().run();
            assertThat(getField(reconnectingProvider, "reconnectScheduled"))
                    .isInstanceOfSatisfying(AtomicBoolean.class, scheduled -> assertThat(scheduled).isFalse());
        } finally {
            reconnectingProvider.stop();
        }
    }

    @Test
    void ignoresMissingAndInvalidSnapshotQuotes() {
        invokeMethod(provider, "seedSnapshots", new ObjectMapper().readTree("""
                {
                  "AAPL": {},
                  "MSFT": {
                    "latestQuote": {
                      "bp": "invalid", "bs": 1, "bx": "V", "ap": 200.20, "as": 1, "ax": "V",
                      "t": "2026-07-23T14:30:00.100Z"
                    }
                  }
                }
                """), new LinkedHashSet<>(List.of("AAPL", "MSFT")));

        assertThatThrownBy(() -> provider.currentQuote(listing()))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> provider.currentQuote(listing(2, "MSFT")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void seedsOnlyReplaceQuotesAndTradesWhenSnapshotDataIsNewer() {
        provider.handlePayload("""
                [
                  {"T":"q","S":"AAPL","bx":"V","bp":199.10,"bs":2,"ax":"V","ap":199.20,"as":3,
                   "t":"2026-07-23T14:30:00.100Z"},
                  {"T":"t","S":"AAPL","x":"V","p":199.15,"s":25,"t":"2026-07-23T14:30:00.200Z"}
                ]
                """);

        seedSnapshots("""
                {
                  "AAPL": {
                    "latestQuote": {
                      "bp": 198.10, "bs": 1, "bx": "V", "ap": 198.20, "as": 1, "ax": "V",
                      "t": "2026-07-23T14:29:00.100Z"
                    },
                    "latestTrade": {
                      "p": 198.15, "s": 10, "t": "2026-07-23T14:29:00.200Z"
                    }
                  }
                }
                """);

        MarketDataService.Quote afterOlderSnapshot = provider.currentQuote(listing());
        assertThat(afterOlderSnapshot.bids().getFirst().price()).isEqualByComparingTo("199.10");
        assertThat(afterOlderSnapshot.lastPrice()).isEqualByComparingTo("199.15");

        seedSnapshots("""
                {
                  "AAPL": {
                    "latestQuote": {
                      "bp": 201.10, "bs": 4, "bx": "V", "ap": 201.20, "as": 5, "ax": "V",
                      "t": "2026-07-23T14:31:00.100Z"
                    },
                    "latestTrade": {
                      "p": 201.15, "s": 30, "t": "2026-07-23T14:31:00.200Z"
                    }
                  }
                }
                """);

        MarketDataService.Quote afterNewerSnapshot = provider.currentQuote(listing());
        assertThat(afterNewerSnapshot.bids().getFirst().price()).isEqualByComparingTo("201.10");
        assertThat(afterNewerSnapshot.lastPrice()).isEqualByComparingTo("201.15");

        seedSnapshots("""
                {
                  "AAPL": {
                    "latestQuote": {
                      "bp": 202.10, "bs": 6, "bx": "V", "ap": 202.20, "as": 7, "ax": "V",
                      "t": "2026-07-23T14:32:00.100Z"
                    }
                  }
                }
                """);

        MarketDataService.Quote afterQuoteOnlySnapshot = provider.currentQuote(listing());
        assertThat(afterQuoteOnlySnapshot.bids().getFirst().price()).isEqualByComparingTo("202.10");
        assertThat(afterQuoteOnlySnapshot.lastPrice()).isEqualByComparingTo("201.15");
        assertThat(afterQuoteOnlySnapshot.asOf()).isEqualTo(Instant.parse("2026-07-23T14:32:00.100Z"));
    }

    @Test
    void seedsInitialTradeAndQuoteFromTheSnapshotEndpoint() throws Exception {
        AtomicReference<String> requestedQuery = new AtomicReference<>();
        AtomicReference<String> requestedKey = new AtomicReference<>();
        AtomicReference<String> requestedSecret = new AtomicReference<>();
        byte[] response = """
                {
                  "AAPL": {
                    "latestQuote": {
                      "bp": 199.10, "bs": 2, "bx": "V", "ap": 199.20, "as": 3, "ax": "V",
                      "t": "2026-07-22T20:00:00.100Z"
                    },
                    "latestTrade": {
                      "p": 199.15, "s": 25, "t": "2026-07-22T20:00:00.200Z"
                    }
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/snapshots", exchange -> {
            requestedQuery.set(exchange.getRequestURI().getQuery());
            requestedKey.set(exchange.getRequestHeaders().getFirst("APCA-API-KEY-ID"));
            requestedSecret.set(exchange.getRequestHeaders().getFirst("APCA-API-SECRET-KEY"));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        AlpacaIexMarketDataProvider snapshotProvider = new AlpacaIexMarketDataProvider(
                new ObjectMapper(),
                URI.create("wss://stream.data.alpaca.markets/v2/test"),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/snapshots?adjusted=true"),
                "test-key",
                "test-secret",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                30
        );
        try {
            MarketDataService.Quote quote = snapshotProvider.quotes(List.of(listing()), Instant.now()).getFirst();

            assertThat(quote.source()).isEqualTo("ALPACA_IEX");
            assertThat(quote.lastPrice()).isEqualByComparingTo("199.15");
            assertThat(quote.bids()).singleElement()
                    .satisfies(level -> assertThat(level.size()).isEqualByComparingTo("200"));
            assertThat(quote.offers()).singleElement()
                    .satisfies(level -> assertThat(level.size()).isEqualByComparingTo("300"));
            assertThat(requestedQuery.get()).contains("adjusted=true", "symbols=AAPL", "feed=iex");
            assertThat(requestedKey.get()).isEqualTo("test-key");
            assertThat(requestedSecret.get()).isEqualTo("test-secret");
        } finally {
            snapshotProvider.stop();
            server.stop(0);
        }
    }

    @Test
    void rejectsRequestsLargerThanTheConfiguredSymbolLimit() {
        AlpacaIexMarketDataProvider limitedProvider = providerWith(
                URI.create("https://data.alpaca.markets/v2/stocks/snapshots"), Duration.ofMillis(20), 2);
        try {
            assertThatThrownBy(() -> limitedProvider.quotes(
                    List.of(listing(1, "AAPL"), listing(2, "MSFT"), listing(3, "NVDA")), Instant.now()))
                    .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        } finally {
            limitedProvider.stop();
        }
    }

    @Test
    void returnsServiceUnavailableWhenSnapshotAndStreamHaveNoQuote() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/snapshots", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        AlpacaIexMarketDataProvider unavailableProvider = providerWith(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/snapshots"),
                Duration.ofMillis(25),
                30
        );
        try {
            assertThatThrownBy(() -> unavailableProvider.quotes(List.of(listing()), Instant.now()))
                    .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
                    .hasMessageContaining("Timed out waiting for Alpaca IEX quotes for AAPL");
        } finally {
            unavailableProvider.stop();
            server.stop(0);
        }
    }

    @Test
    void refusesToStartAlpacaModeWithoutCredentials() {
        AlpacaIexMarketDataProvider missingCredentials = new AlpacaIexMarketDataProvider(
                new ObjectMapper(),
                URI.create("wss://stream.data.alpaca.markets/v2/test"),
                URI.create("https://data.alpaca.markets/v2/stocks/snapshots"),
                "",
                "",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                30
        );
        try {
            assertThatThrownBy(missingCredentials::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("APCA_API_KEY_ID");
        } finally {
            missingCredentials.stop();
        }
    }

    private static ListingSnapshot listing() {
        return listing(1, "AAPL");
    }

    private static ListingSnapshot listing(long id, String symbol) {
        return new ListingSnapshot(id, 1, symbol, symbol + " Inc.", symbol, "XNAS", "Nasdaq", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("200.00"), new BigDecimal("198.00"));
    }

    private static ListingSnapshot listing(
            long id,
            String symbol,
            String marketSymbol,
            BigDecimal previousClose
    ) {
        return new ListingSnapshot(id, 1, symbol, symbol + " Inc.", marketSymbol, "XNAS", "Nasdaq", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("200.00"), previousClose);
    }

    private void markProviderRunning() {
        markProviderRunning(provider);
    }

    private static void markProviderRunning(AlpacaIexMarketDataProvider target) {
        assertThat(getField(target, "running"))
                .isInstanceOfSatisfying(AtomicBoolean.class, running -> running.set(true));
    }

    private void seedSnapshots(String payload) {
        invokeMethod(provider, "seedSnapshots", new ObjectMapper().readTree(payload), Set.of("AAPL"));
    }

    private static WebSocket socket() {
        WebSocket socket = mock(WebSocket.class);
        when(socket.sendText(anyString(), eq(true))).thenReturn(CompletableFuture.completedFuture(socket));
        when(socket.sendClose(anyInt(), anyString())).thenReturn(CompletableFuture.completedFuture(socket));
        return socket;
    }

    private static AlpacaIexMarketDataProvider providerWith(
            URI snapshotsUrl,
            Duration initialDataTimeout,
            int maximumSymbols
    ) {
        return new AlpacaIexMarketDataProvider(
                new ObjectMapper(),
                URI.create("wss://stream.data.alpaca.markets/v2/test"),
                snapshotsUrl,
                "test-key",
                "test-secret",
                initialDataTimeout,
                Duration.ofSeconds(1),
                maximumSymbols
        );
    }
}

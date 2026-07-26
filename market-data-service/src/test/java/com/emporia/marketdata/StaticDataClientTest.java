package com.emporia.marketdata;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class StaticDataClientTest {
    private final List<String> requestUris = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private StaticDataClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/instruments", this::handleRequest);
        server.start();
        client = new StaticDataClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void getsAListingAndForwardsAuthorization() {
        ListingSnapshot listing = client.get(42, "Bearer single-token");

        assertThat(listing.id()).isEqualTo(42);
        assertThat(listing.symbol()).isEqualTo("AAPL");
        assertThat(listing.previousClose()).isEqualByComparingTo("198.00");
        assertThat(requestUris).containsExactly("/instruments/42");
        assertThat(authorizations).containsExactly("Bearer single-token");
    }

    @Test
    void batchesListingsAndSkipsHttpForAnEmptyBatch() {
        List<ListingSnapshot> listings = client.batch(List.of(7L, 9L), "Bearer batch-token");

        assertThat(listings).extracting(ListingSnapshot::id).containsExactly(7L, 9L);
        assertThat(requestUris).containsExactly("/instruments/batch?ids=7,9");
        assertThat(authorizations).containsExactly("Bearer batch-token");

        assertThat(client.batch(List.of(), "Bearer unused-token")).isEmpty();
        assertThat(requestUris).hasSize(1);
        assertThat(authorizations).hasSize(1);
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        requestUris.add(exchange.getRequestURI().toString());
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        String response = switch (exchange.getRequestURI().getPath()) {
            case "/instruments/42" -> listingJson(42, "AAPL");
            case "/instruments/batch" -> "[" + listingJson(7, "MSFT") + "," + listingJson(9, "NVDA") + "]";
            default -> throw new AssertionError("Unexpected request " + exchange.getRequestURI());
        };
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String listingJson(long id, String symbol) {
        return """
                {
                  "id": %d,
                  "version": 1,
                  "symbol": "%s",
                  "name": "%s Inc.",
                  "marketSymbol": "%s",
                  "exchangeMic": "XNAS",
                  "exchangeName": "Nasdaq",
                  "countryCode": "US",
                  "currency": "USD",
                  "tickSize": 0.01,
                  "sizeIncrement": 1,
                  "referencePrice": 200.00,
                  "previousClose": 198.00
                }
                """.formatted(id, symbol, symbol, symbol);
    }
}

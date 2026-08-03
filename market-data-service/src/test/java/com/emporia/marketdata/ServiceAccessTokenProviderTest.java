package com.emporia.marketdata;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.web.client.RestClient;

class ServiceAccessTokenProviderTest {

    @Test
    void obtainsAndCachesAClientCredentialsToken() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/oauth2/token", exchange -> {
            requests.incrementAndGet();
            String expected = "Basic " + Base64.getEncoder()
                    .encodeToString("market-data:secret".getBytes(StandardCharsets.UTF_8));
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo(expected);
            byte[] response = "{\"access_token\":\"service-token\",\"expires_in\":300}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ServiceAccessTokenProvider provider = new ServiceAccessTokenProvider(
                    RestClient.builder(),
                    "http://localhost:" + server.getAddress().getPort() + "/oauth2/token",
                    "market-data", "secret");

            assertThat(provider.authorizationHeader()).isEqualTo("Bearer service-token");
            assertThat(provider.authorizationHeader()).isEqualTo("Bearer service-token");
            assertThat(requests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }
}

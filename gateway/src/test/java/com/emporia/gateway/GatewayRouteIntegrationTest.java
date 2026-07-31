package com.emporia.gateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRouteIntegrationTest {

    private static final String TEST_ISSUER = "https://issuer.emporia.test";
    private static final RSAKey SIGNING_KEY = createSigningKey();
    private static final HttpServer API_UPSTREAM = createApiUpstream();
    private static final HttpServer AUTHORIZATION_SERVER = createAuthorizationServer();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${local.server.port}")
    private int gatewayPort;

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        String apiUrl = "http://127.0.0.1:" + API_UPSTREAM.getAddress().getPort();
        registry.add("emporia.services.static-data-url", () -> apiUrl);
        registry.add("emporia.services.user-preferences-url", () -> apiUrl);
        registry.add("emporia.services.market-data-url", () -> apiUrl);
        registry.add("emporia.services.order-command-url", () -> apiUrl);
        registry.add("emporia.services.order-management-url", () -> apiUrl);
        registry.add("emporia.services.portfolio-url", () -> apiUrl);
        registry.add("emporia.auth.url",
                () -> "http://127.0.0.1:" + AUTHORIZATION_SERVER.getAddress().getPort());
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TEST_ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + AUTHORIZATION_SERVER.getAddress().getPort() + "/oauth2/jwks");
    }

    @AfterAll
    static void stopUpstreams() {
        API_UPSTREAM.stop(0);
        AUTHORIZATION_SERVER.stop(0);
    }

    @Test
    void rejectsApiRequestsWithoutAnAccessToken() throws Exception {
        HttpResponse<String> response = send("/api/orders", "unauthenticated-request", null);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void validatesTokenAndProxiesApiRequests() throws Exception {
        HttpResponse<String> response = send("/api/orders", "integration-request", accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("upstream-path=/orders");
        assertThat(response.headers().firstValue("X-Seen-Authorization")).contains("true");
        assertThat(response.headers().firstValue("X-Seen-Request-Id"))
                .contains("integration-request");
        assertThat(response.headers().firstValue("X-Request-Id"))
                .contains("integration-request");
    }

    @Test
    void proxiesCancelAllToOrderCommandService() throws Exception {
        HttpResponse<String> response = send(
                "/api/orders/cancel-all", null, accessToken(), "POST");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("upstream-path=/orders/cancel-all");
    }

    @Test
    void proxiesWatchlistRequestsToUserPreferencesService() throws Exception {
        HttpResponse<String> response = send("/api/watchlist", null, accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("upstream-path=/watchlist");
    }

    @Test
    void proxiesWorkspacePreferenceWritesToUserPreferencesService() throws Exception {
        HttpResponse<String> response = send(
                "/api/workspace-preferences", null, accessToken(), "PUT");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("upstream-path=/workspace-preferences");
    }

    @Test
    void proxiesAdminUserManagementToAuthorisationService() throws Exception {
        HttpResponse<String> response = send("/api/admin/users", null, accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("auth-path=/admin/users");
    }

    @Test
    void proxiesAdminAuditReadsToAuthorisationService() throws Exception {
        HttpResponse<String> response = send("/api/admin/audit/events", null, accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("auth-path=/admin/audit/events");
    }

    @Test
    void proxiesAdminStaticDataReadsToStaticDataService() throws Exception {
        HttpResponse<String> response = send("/api/admin/static-data/listings", null, accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("upstream-path=/admin/static-data/listings");
    }

    @Test
    void proxiesAdminStaticDataWritesToStaticDataService() throws Exception {
        HttpResponse<String> response = send(
                "/api/admin/static-data/listings/10",
                null,
                accessToken(),
                "PUT");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("upstream-path=/admin/static-data/listings/10");
    }

    @Test
    void proxiesAdminStaticDataImportsToStaticDataService() throws Exception {
        HttpResponse<String> response = send(
                "/api/admin/static-data/listings/import",
                null,
                accessToken(),
                "POST");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("upstream-path=/admin/static-data/listings/import");
    }

    @Test
    void proxiesAuthenticatedMarketDataStreams() throws Exception {
        HttpResponse<String> response = send(
                "/api/market-data/stream?listingIds=1,2", null, accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("upstream-path=/market-data/stream");
        assertThat(response.headers().firstValue("X-Seen-Authorization")).contains("true");
    }

    @Test
    void proxiesPortfolioStateReadsToPortfolioService() throws Exception {
        HttpResponse<String> response = send("/api/portfolio/state/100", null, accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("upstream-path=/portfolio/state/100");
    }

    @Test
    void proxiesPortfolioProvisioningToPortfolioService() throws Exception {
        HttpResponse<String> response = send(
                "/api/portfolio/state/100",
                null,
                accessToken(),
                "POST");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("upstream-path=/portfolio/state/100");
    }

    @Test
    void proxiesPortfolioAuditReadsToPortfolioService() throws Exception {
        HttpResponse<String> response = send("/api/portfolio/audit/events", null, accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("upstream-path=/portfolio/audit/events");
    }

    @Test
    void generatesRequestIdForAuthenticatedRequests() throws Exception {
        HttpResponse<String> response = send("/api/instruments", null, accessToken());

        String generatedRequestId = response.headers()
                .firstValue("X-Request-Id")
                .orElseThrow();

        assertThat(generatedRequestId).isNotBlank();
        assertThat(response.headers().firstValue("X-Seen-Request-Id"))
                .contains(generatedRequestId);
    }

    @Test
    void proxiesOpenIdDiscoveryWithoutAnAccessToken() throws Exception {
        HttpResponse<String> response = send("/.well-known/openid-configuration", null, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"issuer\":\"" + TEST_ISSUER + "\"");
    }

    private HttpResponse<String> send(String path, String requestId, String accessToken) throws Exception {
        return send(path, requestId, accessToken, "GET");
    }

    private HttpResponse<String> send(String path, String requestId, String accessToken,
                                      String method) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + gatewayPort + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (requestId != null) {
            request.header("X-Request-Id", requestId);
        }
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        return HTTP_CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String accessToken() throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(TEST_ISSUER)
                .subject("integration-user")
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("scope", "openid profile")
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID()).build(),
                claims
        );
        jwt.sign(new RSASSASigner(SIGNING_KEY));
        return jwt.serialize();
    }

    private static RSAKey createSigningKey() {
        try {
            return new RSAKeyGenerator(2048)
                    .keyID("gateway-integration-key")
                    .generate();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static HttpServer createApiUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                copyRequestIdToResponse(exchange);
                if (exchange.getRequestHeaders().containsKey("Authorization")) {
                    exchange.getResponseHeaders().set("X-Seen-Authorization", "true");
                }
                writeResponse(exchange, "upstream-path=" + exchange.getRequestURI().getPath());
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static HttpServer createAuthorizationServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/oauth2/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                writeResponse(exchange, new JWKSet(SIGNING_KEY.toPublicJWK()).toString());
            });
            server.createContext("/.well-known/openid-configuration", exchange -> {
                copyRequestIdToResponse(exchange);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                writeResponse(exchange, "{\"issuer\":\"" + TEST_ISSUER + "\"}");
            });
            server.createContext("/admin/", exchange -> writeResponse(exchange,
                    "auth-path=" + exchange.getRequestURI().getPath()));
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void copyRequestIdToResponse(HttpExchange exchange) {
        String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
        if (requestId != null) {
            exchange.getResponseHeaders().set("X-Seen-Request-Id", requestId);
        }
    }

    private static void writeResponse(HttpExchange exchange, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        try (var responseBody = exchange.getResponseBody()) {
            responseBody.write(response);
        }
    }
}

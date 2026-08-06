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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.List;
import java.util.Map;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final AtomicInteger ORDER_FAILURE_CALLS = new AtomicInteger();

    @Value("${local.server.port}")
    private int gatewayPort;

    @Autowired
    private MeterRegistry meters;

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        String apiUrl = "http://127.0.0.1:" + API_UPSTREAM.getAddress().getPort();
        registry.add("emporia.services.static-data-url", () -> apiUrl);
        registry.add("emporia.services.user-preferences-url", () -> apiUrl);
        registry.add("emporia.services.market-data-url", () -> apiUrl);
        registry.add("emporia.services.order-management-url", () -> apiUrl);
        registry.add("emporia.services.portfolio-url", () -> apiUrl);
        registry.add("emporia.auth.url",
                () -> "http://127.0.0.1:" + AUTHORIZATION_SERVER.getAddress().getPort());
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TEST_ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + AUTHORIZATION_SERVER.getAddress().getPort() + "/oauth2/jwks");
        registry.add("emporia.gateway.order-rate-limiter.replenish-rate", () -> 1);
        registry.add("emporia.gateway.order-rate-limiter.burst-capacity", () -> 1);
        registry.add("emporia.gateway.order-rate-limiter.requested-tokens", () -> 1);
        registry.add("EMPORIA_GATEWAY_ORDER_CB_WINDOW_SIZE", () -> 2);
        registry.add("EMPORIA_GATEWAY_ORDER_CB_MIN_CALLS", () -> 2);
        registry.add("EMPORIA_GATEWAY_ORDER_CB_FAILURE_RATE", () -> 50);
        registry.add("EMPORIA_GATEWAY_ORDER_CB_OPEN_DURATION", () -> "100ms");
        registry.add("EMPORIA_GATEWAY_ORDER_CB_HALF_OPEN_CALLS", () -> 1);
        registry.add("emporia.gateway.order-rate-limiter.bypass-authorities", () -> "ROLE_INTERNAL_GATEWAY");
        registry.add("emporia.gateway.order-rate-limiter.bypass-claims", () -> "tier=internal");
        registry.add("emporia.gateway.order-rate-limiter.bypass-service-account-claim", () -> "service_account=true");
    }

    @AfterAll
    static void stopUpstreams() {
        API_UPSTREAM.stop(0);
        AUTHORIZATION_SERVER.stop(0);
    }

    @AfterEach
    void allowCircuitBreakerToReset() throws InterruptedException {
        Thread.sleep(150);
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
    void proxiesCancelAllToOrderManagementService() throws Exception {
        HttpResponse<String> response = send(
                "/api/orders/cancel-all", null, accessToken(), "POST");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("upstream-path=/orders/cancel-all");
    }

    @Test
    void rateLimitsRepeatedOrderCommandsPerAuthenticatedSubject() throws Exception {
        String token = accessToken("rate-limited-user");

        HttpResponse<String> first = send("/api/orders/cancel-all", null, token, "POST");
        HttpResponse<String> second = send("/api/orders/cancel-all", null, token, "POST");

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(429);
        assertThat(second.body()).contains("gateway_rate_limited");
        assertThat(second.headers().firstValue("X-RateLimit-Reason"))
                .contains("gateway-order-rate-limit");
        assertThat(meters.get("emporia.gateway.orders.rate_limited").counter().count()).isGreaterThanOrEqualTo(1.0d);
    }

    @Test
    void bypassesRateLimiterForWhitelistedAuthority() throws Exception {
        String token = accessToken("internal-actor", Map.of("authorities", List.of("ROLE_USER", "ROLE_INTERNAL_GATEWAY")));

        HttpResponse<String> first = send("/api/orders/cancel-all", null, token, "POST");
        HttpResponse<String> second = send("/api/orders/cancel-all", null, token, "POST");

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(meters.get("emporia.gateway.orders.rate_limiter_bypassed").counter().count()).isGreaterThanOrEqualTo(2.0d);
    }

    @Test
    void bypassesRateLimiterForWhitelistedClaim() throws Exception {
        String token = accessToken("internal-claim-actor", Map.of("tier", "internal"));

        HttpResponse<String> first = send("/api/orders/cancel-all", null, token, "POST");
        HttpResponse<String> second = send("/api/orders/cancel-all", null, token, "POST");

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
    }

    @Test
    void bypassesRateLimiterForDedicatedServiceAccountClaim() throws Exception {
        String token = accessToken("svc-order-router", Map.of("service_account", true));

        HttpResponse<String> first = send("/api/orders/cancel-all", null, token, "POST");
        HttpResponse<String> second = send("/api/orders/cancel-all", null, token, "POST");

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
    }

    @Test
    void opensCircuitBreakerAfterRepeatedOrderUpstreamFailures() throws Exception {
        ORDER_FAILURE_CALLS.set(0);
        HttpResponse<String> first = send("/api/orders/fail-circuit", null, accessToken("circuit-breaker-user-1"), "POST");
        HttpResponse<String> second = send("/api/orders/fail-circuit", null, accessToken("circuit-breaker-user-2"), "POST");
        HttpResponse<String> third = send("/api/orders/fail-circuit", null, accessToken("circuit-breaker-user-3"), "POST");

        assertThat(first.statusCode()).isEqualTo(503);
        assertThat(second.statusCode()).isEqualTo(503);
        assertThat(third.statusCode()).isEqualTo(503);
        assertThat(third.body()).contains("gateway_order_circuit_open");
        assertThat(third.headers().firstValue("X-Fallback-Reason"))
                .contains("gateway-order-circuit-open");
        assertThat(ORDER_FAILURE_CALLS.get()).isEqualTo(2);
        assertThat(meters.get("emporia.gateway.orders.circuit_open").counter().count()).isGreaterThanOrEqualTo(1.0d);
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
    void proxiesAdminUserManagementToAuthenticationService() throws Exception {
        HttpResponse<String> response = send("/api/admin/users", null, accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("auth-path=/admin/users");
    }

    @Test
    void proxiesAdminAuditReadsToAuthenticationService() throws Exception {
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
        return accessToken("integration-user-" + UUID.randomUUID());
    }

    private static String accessToken(String subject) throws Exception {
        return accessToken(subject, Map.of());
        }

        private static String accessToken(String subject, Map<String, Object> extraClaims) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(TEST_ISSUER)
                .subject(subject)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
            .claim("scope", "openid profile");
        extraClaims.forEach(builder::claim);
        JWTClaimsSet claims = builder.build();
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
                if (exchange.getRequestURI().getPath().contains("/orders/fail-circuit")) {
                    ORDER_FAILURE_CALLS.incrementAndGet();
                    copyRequestIdToResponse(exchange);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain");
                    byte[] response = "upstream-order-failure".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(503, response.length);
                    try (var responseBody = exchange.getResponseBody()) {
                        responseBody.write(response);
                    }
                    return;
                }
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

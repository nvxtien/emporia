package com.emporia.marketdata;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
final class ServiceAccessTokenProvider {
    private static final ParameterizedTypeReference<Map<String, Object>> TOKEN_BODY = new ParameterizedTypeReference<>() { };

    private final RestClient tokenClient;
    private final String clientId;
    private final String clientSecret;
    private final AtomicReference<CachedToken> cached = new AtomicReference<>();

    ServiceAccessTokenProvider(
            @Value("${emporia.market-data.service-auth.token-url}") String tokenUrl,
            @Value("${emporia.market-data.service-auth.client-id}") String clientId,
            @Value("${emporia.market-data.service-auth.client-secret}") String clientSecret
    ) {
        this.tokenClient = RestClient.builder().baseUrl(tokenUrl).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    String authorizationHeader() {
        CachedToken current = cached.get();
        Instant now = Instant.now();
        if (current != null && current.expiresAt().isAfter(now.plusSeconds(15))) {
            return "Bearer " + current.value();
        }
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new IllegalStateException("Market-data OAuth client credentials are not configured");
        }

        Map<String, Object> body = tokenClient
                .post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(HttpHeaders.AUTHORIZATION, basicAuthentication())
                .body("grant_type=client_credentials&scope=internal")
                .retrieve()
                .body(TOKEN_BODY);
        if (body == null || !(body.get("access_token") instanceof String token) || token.isBlank()) {
            throw new IllegalStateException("Authorisation server returned no service access token");
        }
        long expiresIn = body.get("expires_in") instanceof Number seconds ? seconds.longValue() : 300;
        CachedToken replacement = new CachedToken(token, now.plusSeconds(Math.max(30, expiresIn)));
        cached.set(replacement);
        return "Bearer " + replacement.value();
    }

    private String basicAuthentication() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(clientId, clientSecret);
        return headers.getFirst(HttpHeaders.AUTHORIZATION);
    }

    private record CachedToken(String value, Instant expiresAt) { }
}

package com.emporia.execution;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

@Component
class ServiceAccessTokenProvider {
    private final RestClient tokens;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock = Clock.systemUTC();
    private volatile CachedToken cached;

    @Autowired
    ServiceAccessTokenProvider(RestClient.Builder builder,
            @Value("${emporia.auth.token-url}") String tokenUrl,
            @Value("${emporia.auth.client-id}") String clientId,
            @Value("${emporia.auth.client-secret}") String clientSecret) {
        this(builder.baseUrl(tokenUrl).build(), clientId, clientSecret);
    }

    ServiceAccessTokenProvider(RestClient tokens, String clientId, String clientSecret) {
        this.tokens = tokens;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    synchronized String authorization() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.expiresAt().minusSeconds(15))) {
            return "Bearer " + cached.value();
        }
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", "internal");
        Map<?, ?> response = tokens.post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                .body(form)
                .retrieve()
                .body(Map.class);
        if (response == null || !(response.get("access_token") instanceof String value)) {
            throw new IllegalStateException("Authorisation service returned no execution access token");
        }
        int expiresIn = response.get("expires_in") instanceof Number number ? number.intValue() : 600;
        cached = new CachedToken(value, now.plusSeconds(expiresIn));
        return "Bearer " + value;
    }

    private record CachedToken(String value, Instant expiresAt) {
    }
}

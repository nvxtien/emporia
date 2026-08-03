package com.emporia.ordercommand;

import com.emporia.events.TradingEvents.ListingSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class StaticDataClient {
    private final RestClient client;

    /**
     * Takes the auto-configured builder rather than calling
     * {@code RestClient.builder()}.
     *
     * <p>HTTP client observation is applied by {@code ObservationRestClientCustomizer},
     * which Spring Boot passes to builders through {@code RestClientBuilderConfigurer} —
     * and that only runs for the auto-configured {@code RestClient.Builder} bean.
     * A statically created builder bypasses it entirely, so the client produces
     * no {@code http_client_requests} metrics and no client spans, silently.
     */
    @Autowired
    StaticDataClient(RestClient.Builder builder, @Value("${emporia.static-data.url}") String baseUrl) {
        this(builder.baseUrl(baseUrl).build());
    }

    StaticDataClient(RestClient client) {
        this.client = client;
    }

    ListingSnapshot get(long id, String authorization) {
        return client.get().uri("/instruments/{id}", id).header(HttpHeaders.AUTHORIZATION, authorization)
                .retrieve().body(ListingSnapshot.class);
    }
}

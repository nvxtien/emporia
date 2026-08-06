package com.emporia.ordermanagement.client;

import com.emporia.events.TradingEvents.ListingSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class StaticDataClient {
    private final RestClient client;

    @Autowired
    public StaticDataClient(RestClient.Builder builder, @Value("${emporia.static-data.url:http://localhost:8081}") String baseUrl) {
        this(builder.baseUrl(baseUrl).build());
    }

    public StaticDataClient(RestClient client) {
        this.client = client;
    }

    @Cacheable(value = "listings", key = "#id")
    public ListingSnapshot get(long id, String authorization) {
        return client.get().uri("/instruments/{id}", id).header(HttpHeaders.AUTHORIZATION, authorization)
                .retrieve().body(ListingSnapshot.class);
    }
}

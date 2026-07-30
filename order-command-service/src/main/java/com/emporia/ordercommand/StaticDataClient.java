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

    @Autowired
    StaticDataClient(@Value("${emporia.static-data.url}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).build());
    }

    StaticDataClient(RestClient client) {
        this.client = client;
    }

    ListingSnapshot get(long id, String authorization) {
        return client.get().uri("/instruments/{id}", id).header(HttpHeaders.AUTHORIZATION, authorization)
                .retrieve().body(ListingSnapshot.class);
    }
}

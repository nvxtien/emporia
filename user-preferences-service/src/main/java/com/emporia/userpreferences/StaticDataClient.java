package com.emporia.userpreferences;

import com.emporia.events.TradingEvents.ListingSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Component
class StaticDataClient {
    private static final ParameterizedTypeReference<List<ListingSnapshot>> LISTINGS = new ParameterizedTypeReference<>() { };
    private final RestClient client;

    StaticDataClient(@Value("${emporia.static-data.url}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    ListingSnapshot get(long id, String authorization) {
        return client.get().uri("/instruments/{id}", id).header(HttpHeaders.AUTHORIZATION, authorization)
                .retrieve().body(ListingSnapshot.class);
    }

    List<ListingSnapshot> batch(Collection<Long> ids, String authorization) {
        if (ids.isEmpty()) return List.of();
        String value = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        return client.get().uri(uri -> uri.path("/instruments/batch").queryParam("ids", value).build())
                .header(HttpHeaders.AUTHORIZATION, authorization).retrieve().body(LISTINGS);
    }

    List<ListingSnapshot> bySymbols(Collection<String> symbols, String authorization) {
        String value = String.join(",", symbols);
        return client.get().uri(uri -> uri.path("/instruments/by-symbols").queryParam("symbols", value).build())
                .header(HttpHeaders.AUTHORIZATION, authorization).retrieve().body(LISTINGS);
    }
}

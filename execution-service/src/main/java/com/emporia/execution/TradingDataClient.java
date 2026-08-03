package com.emporia.execution;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.ExecutionRecoveryView;
import com.emporia.events.TradingEvents.StrategyStateView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Component
class TradingDataClient {
    private final RestClient staticData;
    private final RestClient marketData;
    private final RestClient orderManagement;
    private final ServiceAccessTokenProvider tokens;

    TradingDataClient(RestClient.Builder builder, ServiceAccessTokenProvider tokens,
                      @Value("${emporia.services.static-data-url}") String staticDataUrl,
                      @Value("${emporia.services.market-data-url}") String marketDataUrl,
                      @Value("${emporia.services.order-management-url}") String orderManagementUrl) {
        this.staticData = builder.clone().baseUrl(staticDataUrl).build();
        this.marketData = builder.clone().baseUrl(marketDataUrl).build();
        this.orderManagement = builder.clone().baseUrl(orderManagementUrl).build();
        this.tokens = tokens;
    }

    List<ListingSnapshot> sameInstrument(long listingId) {
        List<ListingSnapshot> result = staticData.get()
                .uri("/instruments/{id}/same-instrument", listingId)
                .header(HttpHeaders.AUTHORIZATION, tokens.authorization())
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return result == null ? List.of() : result;
    }

    List<MarketQuote> quotes(List<Long> listingIds) {
        if (listingIds.isEmpty()) return List.of();
        String ids = listingIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        // Passed as a template with a variable, not a pre-built string. The
        // client observation records the `uri` tag from the template, so
        // building the full URL here would tag every distinct listing-id
        // combination separately - unbounded metric cardinality on a hot path.
        List<MarketQuote> result = marketData.get()
                .uri("/market-data/quotes?listingIds={listingIds}", ids)
                .header(HttpHeaders.AUTHORIZATION, tokens.authorization())
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return result == null ? List.of() : result;
    }

    StrategyStateView strategy(UUID parentId) {
        return orderManagement.get()
                .uri("/internal/execution/strategies/{parentId}", parentId)
                .header(HttpHeaders.AUTHORIZATION, tokens.authorization())
                .retrieve()
                .body(StrategyStateView.class);
    }

    ExecutionRecoveryView recoverable() {
        ExecutionRecoveryView result = orderManagement.get()
                .uri("/internal/execution/recoverable")
                .header(HttpHeaders.AUTHORIZATION, tokens.authorization())
                .retrieve()
                .body(ExecutionRecoveryView.class);
        return result == null ? new ExecutionRecoveryView(List.of(), List.of()) : result;
    }

    record MarketQuote(long listingId, BigDecimal lastPrice, List<DepthLevel> bids, List<DepthLevel> offers) {
        MarketQuote {
            bids = bids == null ? List.of() : List.copyOf(bids);
            offers = offers == null ? List.of() : List.copyOf(offers);
        }
    }

    record DepthLevel(BigDecimal price, BigDecimal size, String exchangeMic, String entryId, long listingId) {
    }
}

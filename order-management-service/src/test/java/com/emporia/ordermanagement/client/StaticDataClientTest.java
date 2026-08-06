package com.emporia.ordermanagement.client;

import com.emporia.events.TradingEvents.ListingSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StaticDataClientTest {

    @Test
    void fetchesListingFromRestClient() {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        ListingSnapshot expected = new ListingSnapshot(
                100L, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq",
                "US", "USD", new BigDecimal("0.01"), new BigDecimal("0.01"),
                new BigDecimal("200"), new BigDecimal("198")
        );

        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(eq("/instruments/{id}"), eq(100L))).thenReturn(headersSpec);
        when(headersSpec.header(eq("Authorization"), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(ListingSnapshot.class)).thenReturn(expected);

        StaticDataClient client = new StaticDataClient(restClient);
        ListingSnapshot result = client.get(100L, "Bearer token");

        assertThat(result).isEqualTo(expected);
    }
}

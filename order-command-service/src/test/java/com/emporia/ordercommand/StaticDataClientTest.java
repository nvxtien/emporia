package com.emporia.ordercommand;

import com.emporia.events.TradingEvents.ListingSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class StaticDataClientTest {
    private StaticDataClient client;
    private MockRestServiceServer server;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8081");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new StaticDataClient(builder.build());
    }

    @AfterEach
    void tearDown() {
        server.verify();
    }

    @Test
    void getListingSnapshotSuccess() throws Exception {
        ListingSnapshot listing = new ListingSnapshot(1L, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("200"), new BigDecimal("198"));

        server.expect(requestTo("http://localhost:8081/instruments/1"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-123"))
                .andRespond(withSuccess(objectMapper.writeValueAsString(listing), MediaType.APPLICATION_JSON));

        ListingSnapshot result = client.get(1L, "Bearer token-123");
        assertThat(result).isNotNull();
        assertThat(result.symbol()).isEqualTo("AAPL");
    }

    @Test
    void baseUrlConstructorInstantiation() {
        // Takes the injected builder now: a statically created one skips
        // ObservationRestClientCustomizer, so the client emits no
        // http_client_requests metrics and no client spans.
        StaticDataClient directClient =
                new StaticDataClient(RestClient.builder(), "http://localhost:8081");
        assertThat(directClient).isNotNull();
    }
}

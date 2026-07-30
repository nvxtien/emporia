package com.emporia.userpreferences;

import com.emporia.events.TradingEvents.ListingSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class StaticDataClientTest {
    private MockRestServiceServer server;
    private StaticDataClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://static-data");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new StaticDataClient(builder.build());
    }

    @Test
    void getListingById() throws Exception {
        ListingSnapshot snapshot = listing(10L, "AAPL", "XNAS");
        server.expect(requestTo("http://static-data/instruments/10"))
                .andExpect(header("Authorization", "Bearer token"))
                .andRespond(withSuccess(mapper.writeValueAsString(snapshot), MediaType.APPLICATION_JSON));

        ListingSnapshot result = client.get(10L, "Bearer token");
        assertThat(result.symbol()).isEqualTo("AAPL");
        server.verify();
    }

    @Test
    void batchListingsByIds() throws Exception {
        assertThat(client.batch(List.of(), "Bearer token")).isEmpty();

        ListingSnapshot snapshot = listing(10L, "AAPL", "XNAS");
        server.expect(requestTo("http://static-data/instruments/batch?ids=10"))
                .andExpect(header("Authorization", "Bearer token"))
                .andRespond(withSuccess(mapper.writeValueAsString(List.of(snapshot)), MediaType.APPLICATION_JSON));

        List<ListingSnapshot> results = client.batch(List.of(10L), "Bearer token");
        assertThat(results).hasSize(1);
        server.verify();
    }

    @Test
    void bySymbolsListings() throws Exception {
        ListingSnapshot snapshot = listing(10L, "AAPL", "XNAS");
        server.expect(requestTo("http://static-data/instruments/by-symbols?symbols=AAPL"))
                .andExpect(header("Authorization", "Bearer token"))
                .andRespond(withSuccess(mapper.writeValueAsString(List.of(snapshot)), MediaType.APPLICATION_JSON));

        List<ListingSnapshot> results = client.bySymbols(List.of("AAPL"), "Bearer token");
        assertThat(results).hasSize(1);
        server.verify();
    }

    private static ListingSnapshot listing(long id, String symbol, String mic) {
        return new ListingSnapshot(id, 1, symbol, symbol + " Inc", symbol, mic, "Exchange", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"));
    }
}

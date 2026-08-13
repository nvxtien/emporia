package com.emporia.execution;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.ExecutionRecoveryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TradingDataClientTest {
    private MockRestServiceServer server;
    private TradingDataClient client;
    private final ServiceAccessTokenProvider tokens = mock(ServiceAccessTokenProvider.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(tokens.authorization()).thenReturn("Bearer token");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TradingDataClient(builder, tokens, "http://static-data", "http://market-data", "http://order-mgmt");
    }

    @Test
    void sameInstrumentFetchesSameInstrumentListings() throws Exception {
        ListingSnapshot listing = new ListingSnapshot(1L, 1, "AAPL", "Apple Inc", "AAPL", "XNAS", "Exchange", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("150"), new BigDecimal("150"));

        server.expect(requestTo("http://static-data/instruments/1/same-instrument"))
                .andExpect(header("Authorization", "Bearer token"))
                .andRespond(withSuccess(mapper.writeValueAsString(List.of(listing)), MediaType.APPLICATION_JSON));

        List<ListingSnapshot> result = client.sameInstrument(1L);
        assertThat(result).hasSize(1);
        server.verify();
    }

    @Test
    void quotesEmptyListReturnsEmpty() {
        assertThat(client.quotes(List.of())).isEmpty();
    }

    @Test
    void quotesFetchesMarketQuotes() throws Exception {
        TradingDataClient.MarketQuote quote = new TradingDataClient.MarketQuote(1L, new BigDecimal("150.00"), List.of(), List.of());

        server.expect(requestTo("http://market-data/market-data/quotes?listingIds=1"))
                .andExpect(header("Authorization", "Bearer token"))
                .andRespond(withSuccess(mapper.writeValueAsString(List.of(quote)), MediaType.APPLICATION_JSON));

        List<TradingDataClient.MarketQuote> quotes = client.quotes(List.of(1L));
        assertThat(quotes).hasSize(1);
        server.verify();
    }

    @Test
    void recoverableReturnsView() throws Exception {
        ExecutionRecoveryView recoveryView = new ExecutionRecoveryView(List.of(), List.of());

        server.expect(requestTo("http://order-mgmt/internal/execution/recoverable"))
                .andExpect(header("Authorization", "Bearer token"))
                .andRespond(withSuccess(mapper.writeValueAsString(recoveryView), MediaType.APPLICATION_JSON));

        ExecutionRecoveryView result = client.recoverable();
        assertThat(result).isNotNull();
        server.verify();
    }

    @Test
    void strategyFetchesStrategyState() throws Exception {
        java.util.UUID parentId = java.util.UUID.randomUUID();
        com.emporia.events.TradingEvents.ListingSnapshot listing =
                new com.emporia.events.TradingEvents.ListingSnapshot(
                        1L, 1, "AAPL", "Apple Inc", "AAPL", "XNAS", "Exchange", "US", "USD",
                        new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("150"), new BigDecimal("150"));
        com.emporia.events.TradingEvents.OrderView order =
                new com.emporia.events.TradingEvents.OrderView(
                        parentId, 1, "user", "desk", listing,
                        com.emporia.events.TradingEvents.OrderSide.BUY,
                        com.emporia.events.TradingEvents.OrderType.LIMIT,
                        new BigDecimal("10"), new BigDecimal("100"),
                        new BigDecimal("10"), BigDecimal.ZERO, null,
                        com.emporia.events.TradingEvents.OrderStatus.LIVE,
                        com.emporia.events.TradingEvents.OrderStatus.LIVE,
                        "SMART", "ref", null, parentId, null, null,
                        java.time.Instant.now(), java.time.Instant.now());
        com.emporia.events.TradingEvents.StrategyStateView view =
                new com.emporia.events.TradingEvents.StrategyStateView(order, List.of());

        server.expect(requestTo("http://order-mgmt/internal/execution/strategies/" + parentId))
                .andExpect(header("Authorization", "Bearer token"))
                .andRespond(withSuccess(mapper.writeValueAsString(view), MediaType.APPLICATION_JSON));

        com.emporia.events.TradingEvents.StrategyStateView result = client.strategy(parentId);
        assertThat(result).isNotNull();
        assertThat(result.parent().id()).isEqualTo(parentId);
        server.verify();
    }

    @Test
    void marketQuoteNullListsDefaultToEmpty() {
        TradingDataClient.MarketQuote quote = new TradingDataClient.MarketQuote(1L, new BigDecimal("100"), null, null);
        assertThat(quote.bids()).isEmpty();
        assertThat(quote.offers()).isEmpty();
    }
}

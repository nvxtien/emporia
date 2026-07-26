package com.emporia.marketdata;

import com.emporia.events.TradingEvents.ListingSnapshot;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class MarketDataStepDefinitions {
    private AlpacaIexMarketDataProvider provider;
    private ListingSnapshot listing;
    private MarketDataService.Quote quote;

    @Given("an {string} listing with previous close {string}")
    public void aListingWithPreviousClose(String symbol, String previousClose) {
        listing = new ListingSnapshot(
                1,
                1,
                symbol,
                symbol + " Inc.",
                symbol,
                "XNAS",
                "Nasdaq",
                "US",
                "USD",
                new BigDecimal("0.01"),
                BigDecimal.ONE,
                new BigDecimal("200.00"),
                new BigDecimal(previousClose)
        );
        provider = new AlpacaIexMarketDataProvider(
                new ObjectMapper(),
                URI.create("wss://stream.data.alpaca.markets/v2/test"),
                URI.create("https://data.alpaca.markets/v2/stocks/snapshots"),
                "cucumber-key",
                "cucumber-secret",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                30
        );
    }

    @When("Alpaca IEX publishes these market-data messages")
    public void alpacaPublishesMarketDataMessages(String messages) {
        provider.handlePayload(messages);
    }

    @Then("requesting the Emporia quote returns source {string}")
    public void requestingTheQuoteReturnsSource(String source) {
        quote = provider.quotes(List.of(listing), Instant.parse("2026-07-23T14:31:01Z")).getFirst();
        assertThat(quote.source()).isEqualTo(source);
        assertThat(quote.symbol()).isEqualTo(listing.symbol());
    }

    @Then("the last price is {string} with quantity {string}")
    public void theLastPriceAndQuantityAre(String price, String quantity) {
        assertThat(quote.lastPrice()).isEqualByComparingTo(price);
        assertThat(quote.lastQuantity()).isEqualByComparingTo(quantity);
    }

    @Then("the best bid is {string} for {string} shares on {string}")
    public void theBestBidIs(String price, String size, String exchangeMic) {
        assertThat(quote.bids()).singleElement().satisfies(level -> {
            assertThat(level.price()).isEqualByComparingTo(price);
            assertThat(level.size()).isEqualByComparingTo(size);
            assertThat(level.exchangeMic()).isEqualTo(exchangeMic);
        });
    }

    @Then("the bid book is empty")
    public void theBidBookIsEmpty() {
        assertThat(quote.bids()).isEmpty();
    }

    @Then("the best offer is {string} for {string} shares on {string}")
    public void theBestOfferIs(String price, String size, String exchangeMic) {
        assertThat(quote.offers()).singleElement().satisfies(level -> {
            assertThat(level.price()).isEqualByComparingTo(price);
            assertThat(level.size()).isEqualByComparingTo(size);
            assertThat(level.exchangeMic()).isEqualTo(exchangeMic);
        });
    }

    @After
    public void stopProvider() {
        if (provider != null) {
            provider.stop();
        }
    }
}

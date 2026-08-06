package com.emporia.marketdata;

import com.emporia.marketdata.MarketDataService.Quote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AeronFallbackConfigurationTest {

    @Test
    void noOpPublisherReturnsFalseOnPublish() {
        AeronFallbackConfiguration config = new AeronFallbackConfiguration();
        AeronMarketDataPublisher publisher = config.noOpAeronPublisher();

        Quote quote = new Quote(
                100L, "AAPL", "USD", new BigDecimal("150"), BigDecimal.ONE,
                new BigDecimal("149"), new BigDecimal("2"), BigDecimal.ONE, BigDecimal.TEN,
                java.util.List.of(), java.util.List.of(), java.time.Instant.now(), "TEST"
        );
        boolean result = publisher.publishQuote(quote);

        assertThat(result).isFalse();
    }
}

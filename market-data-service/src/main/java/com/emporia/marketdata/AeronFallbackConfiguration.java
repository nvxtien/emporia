package com.emporia.marketdata;

import com.emporia.marketdata.MarketDataService.Quote;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies a silent no-op {@link AeronMarketDataPublisher} substitute when
 * {@code emporia.market-data.aeron.enabled} is not set to {@code true}.
 *
 * <p>This keeps {@link MarketDataStreamService} injection-clean while preventing any
 * Aeron Media Driver connection attempt in test / simulated-provider environments.
 */
@Configuration
class AeronFallbackConfiguration {

    @Bean
    @ConditionalOnMissingBean(AeronMarketDataPublisher.class)
    AeronMarketDataPublisher noOpAeronPublisher() {
        // Uses the protected no-arg constructor — does not connect to an Aeron Media Driver.
        return new AeronMarketDataPublisher() {
            @Override
            public boolean publishQuote(Quote quote) {
                return false; // silently discard
            }
        };
    }
}

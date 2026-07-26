package com.emporia.marketdata;

import com.emporia.marketdata.MarketDataService.DepthLevel;
import com.emporia.marketdata.MarketDataService.Quote;
import com.emporia.marketdata.grpc.model.ClobQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcQuoteConverterTest {

    @Test
    void preservesTheLegacyGoWireFieldsAndDecimalRepresentation() {
        Quote quote = new Quote(42, "AAPL", "USD", new BigDecimal("199.15"), new BigDecimal("25"),
                new BigDecimal("198"), BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("1234"),
                List.of(new DepthLevel(new BigDecimal("199.10"), new BigDecimal("200"), "IEXG", "bid-1", 41)),
                List.of(), Instant.parse("2026-07-23T10:00:00Z"), "TEST", true, "source reconnecting");

        ClobQuote converted = GrpcQuoteConverter.convert(quote);

        assertThat(converted.getListingId()).isEqualTo(42);
        assertThat(converted.getStreamInterrupted()).isTrue();
        assertThat(converted.getStreamStatusMsg()).isEqualTo("source reconnecting");
        assertThat(converted.getLastPrice().getMantissa()).isEqualTo(19915);
        assertThat(converted.getLastPrice().getExponent()).isEqualTo(-2);
        assertThat(converted.getBids(0).getEntryId()).isEqualTo("bid-1");
        assertThat(converted.getBids(0).getListingId()).isEqualTo(41);
    }
}

package com.emporia.ordermanagement.aeron;

import com.emporia.events.TradingEvents;
import com.emporia.events.TradingEvents.CommandType;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AeronOrderCommandPublisherTest {

    static class TestableAeronOrderCommandPublisher extends AeronOrderCommandPublisher {
        public TestableAeronOrderCommandPublisher() {
            super();
        }
    }

    @Test
    void noArgConstructorHandledSafelyOnPublishAndClose() {
        try (TestableAeronOrderCommandPublisher publisher = new TestableAeronOrderCommandPublisher()) {
            ListingSnapshot listing = new ListingSnapshot(
                    1, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq",
                    "US", "USD", new BigDecimal("0.01"), new BigDecimal("0.01"),
                    new BigDecimal("200"), new BigDecimal("198")
            );
            OrderCommand command = new OrderCommand(
                    TradingEvents.SCHEMA_VERSION,
                    UUID.randomUUID(),
                    CommandType.CREATE,
                    "trader",
                    Instant.now(),
                    UUID.randomUUID(),
                    null,
                    listing,
                    OrderSide.BUY,
                    OrderType.LIMIT,
                    new BigDecimal("10.00"),
                    new BigDecimal("150.00"),
                    "desk-1",
                    "ref-1",
                    null,
                    Map.of()
            );

            boolean result = publisher.publish(command);
            assertThat(result).isFalse();
        }
    }
}


package com.emporia.events.sbe;

import com.emporia.events.TradingEvents;
import com.emporia.events.TradingEvents.CommandType;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderCommandResult;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderType;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SbeEncoderDecoderPropertyTest {

    @Property(tries = 300)
    void orderCommandSbeRoundtripPreservesFields(
            @ForAll @LongRange(min = 1, max = 1_000_000) long qtyLots,
            @ForAll @LongRange(min = 1, max = 10_000_000) long priceTicks,
            @ForAll OrderSide side,
            @ForAll OrderType type,
            @ForAll CommandType commandType
    ) {
        BigDecimal quantity = BigDecimal.valueOf(qtyLots, 2);
        BigDecimal price = BigDecimal.valueOf(priceTicks, 2);
        UUID commandId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        ListingSnapshot listing = new ListingSnapshot(
                100L, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq",
                "US", "USD", new BigDecimal("0.01"), new BigDecimal("0.01"),
                new BigDecimal("200"), new BigDecimal("198")
        );

        OrderCommand command = new OrderCommand(
                TradingEvents.SCHEMA_VERSION,
                commandId,
                commandType,
                "user-subject-123",
                Instant.now(),
                orderId,
                1L,
                listing,
                side,
                type,
                quantity,
                price,
                "DESK-PROP",
                "REF-999",
                null,
                Map.of()
        );

        byte[] encoded = SbeEncoderDecoder.encodeOrderCommand(command);
        assertThat(SbeEncoderDecoder.isSbePayload(encoded)).isTrue();

        OrderCommand decoded = SbeEncoderDecoder.decodeOrderCommand(encoded);

        assertThat(decoded.schemaVersion()).isEqualTo(TradingEvents.SCHEMA_VERSION);
        assertThat(decoded.commandId()).isEqualTo(commandId);
        assertThat(decoded.orderId()).isEqualTo(orderId);
        assertThat(decoded.side()).isEqualTo(side);
        assertThat(decoded.orderType()).isEqualTo(type);
        assertThat(decoded.quantityScaled()).isEqualTo(command.quantityScaled());
        assertThat(decoded.limitPriceScaled()).isEqualTo(command.limitPriceScaled());
    }

    @Property(tries = 300)
    void orderCommandResultSbeRoundtripPreservesStatus(
            @ForAll boolean success,
            @ForAll int statusCode,
            @ForAll("errorMessages") String errorMessage
    ) {
        UUID commandId = UUID.randomUUID();
        OrderCommandResult result = new OrderCommandResult(
                TradingEvents.SCHEMA_VERSION,
                commandId,
                success,
                statusCode,
                errorMessage,
                "{}"
        );

        byte[] encoded = SbeEncoderDecoder.encodeOrderCommandResult(result);
        assertThat(SbeEncoderDecoder.isSbePayload(encoded)).isTrue();

        OrderCommandResult decoded = SbeEncoderDecoder.decodeOrderCommandResult(encoded);

        assertThat(decoded.schemaVersion()).isEqualTo(TradingEvents.SCHEMA_VERSION);
        assertThat(decoded.commandId()).isEqualTo(commandId);
        assertThat(decoded.success()).isEqualTo(success);
        assertThat(decoded.status()).isEqualTo(statusCode);
        if (errorMessage == null || errorMessage.isEmpty()) {
            assertThat(decoded.detail()).isIn(null, "");
        } else {
            assertThat(decoded.detail()).isEqualTo(errorMessage);
        }
    }

    @Provide
    Arbitrary<String> errorMessages() {
        return Arbitraries.strings().alpha().ofMinLength(0).ofMaxLength(50).injectNull(0.3);
    }
}

package com.emporia.events.sbe;

import com.emporia.events.TradingEvents.ExecutionCommand;
import com.emporia.events.TradingEvents.ExecutionCommandType;
import com.emporia.events.TradingEvents.OrderCommandResult;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SbeEncoderDecoderTest {

    @Test
    void encodesAndDecodesOrderDomainEventBinaryPayload() {
        UUID eventId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Instant now = Instant.now();

        OrderDomainEvent original = new OrderDomainEvent(
                1, eventId, commandId, orderId,
                "trader-sub-123", "DESK-ALPHA", "PARTIALLY_FILLED", 42L,
                OrderStatus.PARTIALLY_FILLED, now, "{\"traded\":\"50\"}"
        );

        byte[] binary = SbeEncoderDecoder.encodeOrderDomainEvent(original);
        assertThat(SbeEncoderDecoder.isSbePayload(binary)).isTrue();

        OrderDomainEvent decoded = SbeEncoderDecoder.decodeOrderDomainEvent(binary);
        assertThat(decoded).isNotNull();
        assertThat(decoded.eventId()).isEqualTo(eventId);
        assertThat(decoded.commandId()).isEqualTo(commandId);
        assertThat(decoded.orderId()).isEqualTo(orderId);
        assertThat(decoded.userSubject()).isEqualTo("trader-sub-123");
        assertThat(decoded.deskId()).isEqualTo("DESK-ALPHA");
        assertThat(decoded.eventType()).isEqualTo("PARTIALLY_FILLED");
        assertThat(decoded.orderVersion()).isEqualTo(42L);
        assertThat(decoded.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(decoded.occurredAt().toEpochMilli()).isEqualTo(now.toEpochMilli());
        assertThat(decoded.payload()).isEqualTo("{\"traded\":\"50\"}");
    }

    @Test
    void encodesAndDecodesExecutionCommandBinaryPayload() {
        UUID commandId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Instant now = Instant.now();

        ExecutionCommand original = new ExecutionCommand(
                1, commandId, ExecutionCommandType.FILL,
                orderId, "DESK-BETA", "venue-fill-8899",
                new BigDecimal("12.5000"), new BigDecimal("199.95"),
                "XNAS", now, "partial fill confirmed"
        );

        byte[] binary = SbeEncoderDecoder.encodeExecutionCommand(original);
        assertThat(SbeEncoderDecoder.isSbePayload(binary)).isTrue();

        ExecutionCommand decoded = SbeEncoderDecoder.decodeExecutionCommand(binary);
        assertThat(decoded).isNotNull();
        assertThat(decoded.commandId()).isEqualTo(commandId);
        assertThat(decoded.orderId()).isEqualTo(orderId);
        assertThat(decoded.commandType()).isEqualTo(ExecutionCommandType.FILL);
        assertThat(decoded.deskId()).isEqualTo("DESK-BETA");
        assertThat(decoded.executionReference()).isEqualTo("venue-fill-8899");
        assertThat(decoded.quantity()).isEqualByComparingTo("12.5000");
        assertThat(decoded.price()).isEqualByComparingTo("199.95");
        assertThat(decoded.venue()).isEqualTo("XNAS");
        assertThat(decoded.detail()).isEqualTo("partial fill confirmed");
    }

    /**
     * Verifies the fixed-point wire format: quantity and price are encoded as 8-byte
     * longs (not var-length strings), so the payload must be exactly the fixed size.
     *
     * <p>Old format: qty + price were var-strings ("12.500000" = 9 bytes + 2 len,
     * "199.950000" = 10 bytes + 2 len → 23 extra bytes).
     * New format: two 8-byte longs → 16 bytes fixed, regardless of value.
     */
    @Test
    void executionCommandPayloadIsCompactFixedPoint() {
        UUID commandId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Instant now = Instant.now();

        byte[] deskId = "DESK".getBytes();         // 4 bytes + 2 len = 6
        byte[] ref    = "REF".getBytes();           // 3 bytes + 2 len = 5
        byte[] venue  = "XNAS".getBytes();          // 4 bytes + 2 len = 6
        byte[] detail = "ok".getBytes();            // 2 bytes + 2 len = 4

        int expectedSize =
                4 + 2 + 2      // magic + msgType + schemaVersion
                + 16 + 16      // commandId + orderId
                + 1 + 8        // commandType + occurredAt
                + 8 + 8        // qty fixed-point + price fixed-point
                + 6 + 5 + 6 + 4; // var strings

        ExecutionCommand cmd = new ExecutionCommand(
                1, commandId, ExecutionCommandType.FILL,
                orderId, "DESK", "REF",
                new BigDecimal("12.5"), new BigDecimal("199.95"),
                "XNAS", now, "ok"
        );

        byte[] encoded = SbeEncoderDecoder.encodeExecutionCommand(cmd);
        assertThat(encoded.length).isEqualTo(expectedSize);
    }

    /**
     * Null quantity and price encode as 0L and decode back to null — preserving
     * the existing nullable contract used by REJECT and CANCEL commands.
     */
    @Test
    void executionCommandNullQuantityAndPriceRoundTrip() {
        UUID commandId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        ExecutionCommand original = new ExecutionCommand(
                1, commandId, ExecutionCommandType.CANCEL,
                orderId, "DESK", "ref-cancel-1",
                null, null, "XNAS", Instant.now(), "user requested cancel"
        );

        ExecutionCommand decoded = SbeEncoderDecoder.decodeExecutionCommand(
                SbeEncoderDecoder.encodeExecutionCommand(original));

        assertThat(decoded.quantity()).isNull();
        assertThat(decoded.price()).isNull();
    }

    @Test
    void encodesAndDecodesOrderCommandResultBinaryPayload() {
        UUID commandId = UUID.randomUUID();

        OrderCommandResult original = new OrderCommandResult(
                1, commandId, true, 201, "Created successfully", "{\"id\":\"" + commandId + "\"}"
        );

        byte[] binary = SbeEncoderDecoder.encodeOrderCommandResult(original);
        assertThat(SbeEncoderDecoder.isSbePayload(binary)).isTrue();

        OrderCommandResult decoded = SbeEncoderDecoder.decodeOrderCommandResult(binary);
        assertThat(decoded).isNotNull();
        assertThat(decoded.commandId()).isEqualTo(commandId);
        assertThat(decoded.success()).isTrue();
        assertThat(decoded.status()).isEqualTo(201);
        assertThat(decoded.detail()).isEqualTo("Created successfully");
        assertThat(decoded.payload()).isEqualTo("{\"id\":\"" + commandId + "\"}");
    }

    @Test
    void serializerAndDeserializerIntegration() {
        SbeKafkaSerializer serializer = new SbeKafkaSerializer();
        SbeKafkaDeserializer deserializer = new SbeKafkaDeserializer();

        OrderDomainEvent event = new OrderDomainEvent(
                1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "trader", "desk", "LIVE", 1L, OrderStatus.LIVE, Instant.now(), "{}"
        );

        byte[] serialized = serializer.serialize("emporia.orders.v1", event);
        Object deserialized = deserializer.deserialize("emporia.orders.v1", serialized);

        assertThat(deserialized).isInstanceOf(OrderDomainEvent.class);
        assertThat(((OrderDomainEvent) deserialized).orderVersion()).isEqualTo(1L);
    }

    @Test
    void decodeThrowsOnWrongMsgTypeForExecutionCommand() {
        UUID commandId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        // Encode as OrderCommandResult, then try to decode as ExecutionCommand
        byte[] wrongType = SbeEncoderDecoder.encodeOrderCommandResult(
                new OrderCommandResult(1, commandId, true, 200, null, null));
        assertThatThrownBy(() -> SbeEncoderDecoder.decodeExecutionCommand(wrongType))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid SBE payload for ExecutionCommand");
    }

    @Test
    void roundTripsAnOrderCommandIncludingListingAndParameters() {
        TradingEvents.ListingSnapshot listing = new TradingEvents.ListingSnapshot(
                7L, 3, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq", "US", "USD",
                new java.math.BigDecimal("0.010000"), new java.math.BigDecimal("1.000000"),
                new java.math.BigDecimal("101.000000"), new java.math.BigDecimal("100.000000"));
        TradingEvents.OrderCommand command = new TradingEvents.OrderCommand(
                TradingEvents.SCHEMA_VERSION, UUID.randomUUID(), TradingEvents.CommandType.CREATE,
                "trader-a", "desk-a", Instant.ofEpochMilli(1_780_000_000_000L),
                UUID.randomUUID(), 4L, listing, TradingEvents.OrderSide.SELL,
                TradingEvents.OrderType.LIMIT,
                new java.math.BigDecimal("10.000000"), new java.math.BigDecimal("102.250000"),
                "SMART", "originator-1", UUID.randomUUID(),
                java.util.Map.of("strategy", "SMART", "tif", "IOC"));

        byte[] encoded = SbeEncoderDecoder.encodeOrderCommand(command);
        TradingEvents.OrderCommand decoded = SbeEncoderDecoder.decodeOrderCommand(encoded);

        // A command that does not survive the round trip cannot be replayed,
        // which is the only reason to encode it.
        assertThat(SbeEncoderDecoder.isSbePayload(encoded)).isTrue();
        assertThat(decoded).isEqualTo(command);
    }

    @Test
    void distinguishesAbsentEnumsFromTheirFirstOrdinal() {
        // CREATE, BUY and MARKET are all ordinal 0, so encoding null as 0 would
        // decode an absent side into a BUY - an order in the wrong direction.
        TradingEvents.OrderCommand sparse = new TradingEvents.OrderCommand(
                TradingEvents.SCHEMA_VERSION, UUID.randomUUID(), TradingEvents.CommandType.CANCEL,
                "trader-a", null, Instant.ofEpochMilli(1_780_000_000_000L),
                UUID.randomUUID(), null, null, null, null, null, null, null, null, null, null);

        TradingEvents.OrderCommand decoded =
                SbeEncoderDecoder.decodeOrderCommand(SbeEncoderDecoder.encodeOrderCommand(sparse));

        assertThat(decoded.side()).isNull();
        assertThat(decoded.orderType()).isNull();
        assertThat(decoded.listing()).isNull();
        assertThat(decoded.expectedVersion()).isNull();
        assertThat(decoded.commandType()).isEqualTo(TradingEvents.CommandType.CANCEL);
        // The epoch is a real instant, so it must not decode as absent.
        assertThat(SbeEncoderDecoder.decodeOrderCommand(SbeEncoderDecoder.encodeOrderCommand(
                withRequestedAt(sparse, Instant.EPOCH))).requestedAt()).isEqualTo(Instant.EPOCH);
    }

    private static TradingEvents.OrderCommand withRequestedAt(
            TradingEvents.OrderCommand command, Instant requestedAt) {
        return new TradingEvents.OrderCommand(command.schemaVersion(), command.commandId(),
                command.commandType(), command.userSubject(), command.deskId(), requestedAt,
                command.orderId(), command.expectedVersion(), command.listing(), command.side(),
                command.orderType(), command.quantity(), command.limitPrice(),
                command.destination(), command.originatorReference(), command.parentOrderId(),
                command.executionParameters());
    }

    @Test
    void rejectsAPayloadEncodedAsADifferentMessage() {
        byte[] otherMessage = SbeEncoderDecoder.encodeOrderCommandResult(
                new TradingEvents.OrderCommandResult(TradingEvents.SCHEMA_VERSION,
                        UUID.randomUUID(), true, 201, null, null));

        assertThatThrownBy(() -> SbeEncoderDecoder.decodeOrderCommand(otherMessage))
                .isInstanceOf(IllegalArgumentException.class);
    }


    @Test
    void keepsTheEpochAsATimestampInEveryMessage() {
        // 0 is the epoch, a real instant. Encoding absent as 0 silently dropped
        // the timestamp of any message that carried it.
        TradingEvents.OrderDomainEvent event = new TradingEvents.OrderDomainEvent(
                TradingEvents.SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "trader-a", "desk-a", "CREATED", 1L,
                TradingEvents.OrderStatus.LIVE, Instant.EPOCH, "{}");
        assertThat(SbeEncoderDecoder.decodeOrderDomainEvent(
                SbeEncoderDecoder.encodeOrderDomainEvent(event)).occurredAt())
                .isEqualTo(Instant.EPOCH);

        TradingEvents.ExecutionCommand execution = new TradingEvents.ExecutionCommand(
                TradingEvents.SCHEMA_VERSION, UUID.randomUUID(),
                TradingEvents.ExecutionCommandType.FILL, UUID.randomUUID(), "desk-a",
                "exec-1", java.math.BigDecimal.ONE, java.math.BigDecimal.TEN, "XNAS",
                Instant.EPOCH, "filled");
        assertThat(SbeEncoderDecoder.decodeExecutionCommand(
                SbeEncoderDecoder.encodeExecutionCommand(execution)).occurredAt())
                .isEqualTo(Instant.EPOCH);
    }

    @Test
    void stillDistinguishesAnAbsentTimestamp() {
        TradingEvents.OrderDomainEvent noTimestamp = new TradingEvents.OrderDomainEvent(
                TradingEvents.SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "trader-a", "desk-a", "CREATED", 1L,
                TradingEvents.OrderStatus.LIVE, null, "{}");

        assertThat(SbeEncoderDecoder.decodeOrderDomainEvent(
                SbeEncoderDecoder.encodeOrderDomainEvent(noTimestamp)).occurredAt()).isNull();
    }

}

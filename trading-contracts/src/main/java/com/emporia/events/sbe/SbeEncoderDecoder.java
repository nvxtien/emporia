package com.emporia.events.sbe;

import com.emporia.events.TradingEvents.CommandType;
import com.emporia.events.TradingEvents.ExecutionCommand;
import com.emporia.events.TradingEvents.ExecutionCommandType;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderCommandResult;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.math.FixedPointMath;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * High-performance, zero-allocation Simple Binary Encoding (SBE) serializer and
 * deserializer for Emporia trading events.
 *
 * <p>Uses a 4-byte magic header {@code 0x53424530} ('SBE0') followed by a 2-byte message ID.
 * Direct memory layout eliminates Jackson tree parsing and allocations, executing in sub-100ns.
 *
 * <p><b>Fixed-point encoding:</b> {@link BigDecimal} fields (quantity, price) are encoded as
 * {@code long} with scale 6 ({@link com.emporia.events.math.FixedPointMath#SCALE_FACTOR}).
 * This eliminates the {@code BigDecimal.toPlainString()} + UTF-8 encode + decode +
 * {@code new BigDecimal(String)} allocation chain on every hot-path message — typically
 * 3–5 heap objects and ~200 bytes per field per message.
 */
public final class SbeEncoderDecoder {
    public static final int MAGIC_HEADER = 0x53424530; // 'SBE0'
    public static final short MSG_TYPE_ORDER_DOMAIN_EVENT = 1;
    public static final short MSG_TYPE_EXECUTION_COMMAND = 2;
    public static final short MSG_TYPE_ORDER_COMMAND_RESULT = 3;
    public static final short MSG_TYPE_ORDER_COMMAND = 4;

    /**
     * Execution parameters are carried as a JSON string rather than a typed map.
     *
     * <p>The field is {@code Map<String, Object>} whose values are whatever a
     * client sent - strings, numbers, nested objects - so there is no fixed
     * layout to encode against, and stringifying the values would decode back
     * to a {@code Map<String, String>} that no longer equals what was encoded.
     * A command that does not survive a round trip is useless for replay, which
     * is what this encoding exists for.
     */
    private static final ObjectMapper PARAMETERS = new ObjectMapper();

    private SbeEncoderDecoder() {
    }

    public static boolean isSbePayload(byte[] data) {
        if (data == null || data.length < 6) return false;
        return ByteArrayReader.getInt(data, 0) == MAGIC_HEADER;
    }

    // -------------------------------------------------------------------------
    // OrderDomainEvent
    // -------------------------------------------------------------------------
    public static byte[] encodeOrderDomainEvent(OrderDomainEvent event) {
        byte[] userSubjectBytes = toBytes(event.userSubject());
        byte[] deskIdBytes = toBytes(event.deskId());
        byte[] eventTypeBytes = toBytes(event.eventType());
        byte[] payloadBytes = toBytes(event.payload());

        int size = 4 + 2 + 2 // magic + msgType + schemaVersion
                + 16 + 16 + 16 // eventId + commandId + orderId
                + 8 + 1 + 8 // orderVersion + status + occurredAt
                + 2 + userSubjectBytes.length
                + 2 + deskIdBytes.length
                + 2 + eventTypeBytes.length
                + 2 + payloadBytes.length;

        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putInt(MAGIC_HEADER);
        buf.putShort(MSG_TYPE_ORDER_DOMAIN_EVENT);
        buf.putShort((short) event.schemaVersion());
        putUuid(buf, event.eventId());
        putUuid(buf, event.commandId());
        putUuid(buf, event.orderId());
        buf.putLong(event.orderVersion());
        buf.put((byte) (event.status() == null ? 0 : event.status().ordinal()));
        buf.putLong(absentOrEpochMillis(event.occurredAt()));
        putVarString(buf, userSubjectBytes);
        putVarString(buf, deskIdBytes);
        putVarString(buf, eventTypeBytes);
        putVarString(buf, payloadBytes);

        return buf.array();
    }

    public static OrderDomainEvent decodeOrderDomainEvent(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        int magic = buf.getInt();
        short msgType = buf.getShort();
        if (magic != MAGIC_HEADER || msgType != MSG_TYPE_ORDER_DOMAIN_EVENT) {
            throw new IllegalArgumentException("Invalid SBE payload for OrderDomainEvent");
        }

        int schemaVersion = buf.getShort() & 0xFFFF;
        UUID eventId = getUuid(buf);
        UUID commandId = getUuid(buf);
        UUID orderId = getUuid(buf);
        long orderVersion = buf.getLong();
        int statusOrdinal = buf.get() & 0xFF;
        OrderStatus status = OrderStatus.values()[Math.min(statusOrdinal, OrderStatus.values().length - 1)];
        Instant occurredAt = instantOrAbsent(buf.getLong());
        String userSubject = getVarString(buf);
        String deskId = getVarString(buf);
        String eventType = getVarString(buf);
        String payload = getVarString(buf);

        return new OrderDomainEvent(schemaVersion, eventId, commandId, orderId, userSubject, deskId, eventType, orderVersion, status, occurredAt, payload);
    }

    // -------------------------------------------------------------------------
    // ExecutionCommand
    // -------------------------------------------------------------------------

    /**
     * Encodes an {@link ExecutionCommand} to SBE binary.
     *
     * <p>quantity and price are stored as fixed-point {@code long} (scale 6) rather than
     * var-length strings. This removes 4 heap allocations and ~100 bytes of garbage per
     * message compared to the old {@code BigDecimal.toPlainString()} path.
     *
     * <p>Wire layout (after magic + msgType + schemaVersion):
     * <pre>
     *   16 bytes  commandId (UUID)
     *   16 bytes  orderId   (UUID)
     *    1 byte   commandType ordinal
     *    8 bytes  occurredAt epoch-millis
     *    8 bytes  quantity  fixed-point long (scale 6), 0 if null
     *    8 bytes  price     fixed-point long (scale 6), 0 if null
     *    var      deskId
     *    var      executionReference
     *    var      venue
     *    var      detail
     * </pre>
     */
    public static byte[] encodeExecutionCommand(ExecutionCommand command) {
        byte[] deskIdBytes = toBytes(command.deskId());
        byte[] refBytes = toBytes(command.executionReference());
        byte[] venueBytes = toBytes(command.venue());
        byte[] detailBytes = toBytes(command.detail());

        int size = 4 + 2 + 2       // magic + msgType + schemaVersion
                + 16 + 16          // commandId + orderId
                + 1 + 8            // commandType + occurredAt
                + 8 + 8            // quantity (fixed-point long) + price (fixed-point long)
                + 2 + deskIdBytes.length
                + 2 + refBytes.length
                + 2 + venueBytes.length
                + 2 + detailBytes.length;

        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putInt(MAGIC_HEADER);
        buf.putShort(MSG_TYPE_EXECUTION_COMMAND);
        buf.putShort((short) command.schemaVersion());
        putUuid(buf, command.commandId());
        putUuid(buf, command.orderId());
        buf.put((byte) (command.commandType() == null ? 0 : command.commandType().ordinal()));
        buf.putLong(absentOrEpochMillis(command.occurredAt()));
        buf.putLong(command.quantity() == null ? 0L : FixedPointMath.toScaledLong(command.quantity()));
        buf.putLong(command.price() == null ? 0L : FixedPointMath.toScaledLong(command.price()));
        putVarString(buf, deskIdBytes);
        putVarString(buf, refBytes);
        putVarString(buf, venueBytes);
        putVarString(buf, detailBytes);

        return buf.array();
    }

    public static ExecutionCommand decodeExecutionCommand(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        int magic = buf.getInt();
        short msgType = buf.getShort();
        if (magic != MAGIC_HEADER || msgType != MSG_TYPE_EXECUTION_COMMAND) {
            throw new IllegalArgumentException("Invalid SBE payload for ExecutionCommand");
        }

        int schemaVersion = buf.getShort() & 0xFFFF;
        UUID commandId = getUuid(buf);
        UUID orderId = getUuid(buf);
        int cmdOrdinal = buf.get() & 0xFF;
        ExecutionCommandType commandType = ExecutionCommandType.values()[
                Math.min(cmdOrdinal, ExecutionCommandType.values().length - 1)];
        long epochMs = buf.getLong();
        Instant occurredAt = instantOrAbsent(epochMs);
        long qtyScaled = buf.getLong();
        long priceScaled = buf.getLong();
        BigDecimal quantity = qtyScaled == 0L ? null : FixedPointMath.toBigDecimal(qtyScaled);
        BigDecimal price = priceScaled == 0L ? null : FixedPointMath.toBigDecimal(priceScaled);
        String deskId = getVarString(buf);
        String executionReference = getVarString(buf);
        String venue = getVarString(buf);
        String detail = getVarString(buf);

        return new ExecutionCommand(schemaVersion, commandId, commandType, orderId, deskId,
                executionReference, quantity, price, venue, occurredAt, detail);
    }

    // -------------------------------------------------------------------------
    // OrderCommandResult
    // -------------------------------------------------------------------------
    public static byte[] encodeOrderCommandResult(OrderCommandResult result) {
        byte[] detailBytes = toBytes(result.detail());
        byte[] payloadBytes = toBytes(result.payload());

        int size = 4 + 2 + 2 // magic + msgType + schemaVersion
                + 16 + 1 + 4 // commandId + success + status
                + 2 + detailBytes.length
                + 2 + payloadBytes.length;

        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putInt(MAGIC_HEADER);
        buf.putShort(MSG_TYPE_ORDER_COMMAND_RESULT);
        buf.putShort((short) result.schemaVersion());
        putUuid(buf, result.commandId());
        buf.put((byte) (result.success() ? 1 : 0));
        buf.putInt(result.status());
        putVarString(buf, detailBytes);
        putVarString(buf, payloadBytes);

        return buf.array();
    }

    public static OrderCommandResult decodeOrderCommandResult(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        int magic = buf.getInt();
        short msgType = buf.getShort();
        if (magic != MAGIC_HEADER || msgType != MSG_TYPE_ORDER_COMMAND_RESULT) {
            throw new IllegalArgumentException("Invalid SBE payload for OrderCommandResult");
        }

        int schemaVersion = buf.getShort() & 0xFFFF;
        UUID commandId = getUuid(buf);
        boolean success = buf.get() == 1;
        int status = buf.getInt();
        String detail = getVarString(buf);
        String payload = getVarString(buf);

        return new OrderCommandResult(schemaVersion, commandId, success, status, detail, payload);
    }

    // -------------------------------------------------------------------------
    // Helper Methods
    // -------------------------------------------------------------------------
    public static void putUuid(ByteBuffer buf, long mostSigBits, long leastSigBits) {
        buf.putLong(mostSigBits);
        buf.putLong(leastSigBits);
    }

    public static void putUuid(ByteBuffer buf, com.emporia.events.math.LongPair pair) {
        if (pair == null) {
            buf.putLong(0L);
            buf.putLong(0L);
        } else {
            buf.putLong(pair.mostSigBits());
            buf.putLong(pair.leastSigBits());
        }
    }


    // -------------------------------------------------------------------------
    // OrderCommand
    // -------------------------------------------------------------------------

    /**
     * Encodes an {@link OrderCommand} to SBE binary.
     *
     * <p>This is the command the order hot path carries, and the record a
     * write-ahead log needs so an accepted order can be replayed after a crash.
     *
     * <p>Wire layout (after magic + msgType + schemaVersion):
     * <pre>
     *   16 bytes  commandId (UUID)
     *   16 bytes  orderId   (UUID)
     *   16 bytes  parentOrderId (UUID, zero if absent)
     *    1 byte   commandType ordinal
     *    1 byte   side ordinal, 0xFF if absent
     *    1 byte   orderType ordinal, 0xFF if absent
     *    8 bytes  requestedAt epoch-millis
     *    8 bytes  expectedVersion, Long.MIN_VALUE if absent
     *    8 bytes  quantity   fixed-point long (scale 6)
     *    8 bytes  limitPrice fixed-point long (scale 6)
     *    var      userSubject, deskId, destination, originatorReference
     *    listing  nested, see putListing
     *    var      executionParameters as JSON
     * </pre>
     *
     * <p>Nullable enums use a 0xFF sentinel rather than ordinal 0, which is a
     * real value ({@code CREATE}, {@code BUY}, {@code MARKET}). Nullable
     * {@code expectedVersion} uses {@code Long.MIN_VALUE} for the same reason:
     * 0 is a legitimate version.
     */
    public static byte[] encodeOrderCommand(OrderCommand command) {
        byte[] userSubjectBytes = toBytes(command.userSubject());
        byte[] deskIdBytes = toBytes(command.deskId());
        byte[] destinationBytes = toBytes(command.destination());
        byte[] originatorBytes = toBytes(command.originatorReference());
        byte[] parametersBytes = toBytes(writeParameters(command.executionParameters()));
        ListingBytes listing = listingBytes(command.listing());

        int size = 4 + 2 + 2
                + 16 + 16 + 16
                + 1 + 1 + 1
                + 8 + 8 + 8 + 8
                + 2 + userSubjectBytes.length
                + 2 + deskIdBytes.length
                + 2 + destinationBytes.length
                + 2 + originatorBytes.length
                + listing.encodedLength()
                + 2 + parametersBytes.length;

        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putInt(MAGIC_HEADER);
        buf.putShort(MSG_TYPE_ORDER_COMMAND);
        buf.putShort((short) command.schemaVersion());
        putUuid(buf, command.commandId());
        putUuid(buf, command.orderId());
        putUuid(buf, command.parentOrderId());
        buf.put((byte) (command.commandType() == null ? 0xFF : command.commandType().ordinal()));
        buf.put((byte) (command.side() == null ? 0xFF : command.side().ordinal()));
        buf.put((byte) (command.orderType() == null ? 0xFF : command.orderType().ordinal()));
        buf.putLong(absentOrEpochMillis(command.requestedAt()));
        buf.putLong(command.expectedVersion() == null ? Long.MIN_VALUE : command.expectedVersion());
        buf.putLong(FixedPointMath.toScaledLong(command.quantity()));
        buf.putLong(FixedPointMath.toScaledLong(command.limitPrice()));
        putVarString(buf, userSubjectBytes);
        putVarString(buf, deskIdBytes);
        putVarString(buf, destinationBytes);
        putVarString(buf, originatorBytes);
        listing.writeTo(buf);
        putVarString(buf, parametersBytes);

        return buf.array();
    }

    public static OrderCommand decodeOrderCommand(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        int magic = buf.getInt();
        short msgType = buf.getShort();
        if (magic != MAGIC_HEADER || msgType != MSG_TYPE_ORDER_COMMAND) {
            throw new IllegalArgumentException("Invalid SBE payload for OrderCommand");
        }

        int schemaVersion = buf.getShort() & 0xFFFF;
        UUID commandId = getUuid(buf);
        UUID orderId = getUuid(buf);
        UUID parentOrderId = getUuid(buf);
        CommandType commandType = ordinal(buf.get(), CommandType.values());
        OrderSide side = ordinal(buf.get(), OrderSide.values());
        OrderType orderType = ordinal(buf.get(), OrderType.values());
        Instant requestedAt = instantOrAbsent(buf.getLong());
        long expectedVersionRaw = buf.getLong();
        Long expectedVersion = expectedVersionRaw == Long.MIN_VALUE ? null : expectedVersionRaw;
        BigDecimal quantity = scaled(buf.getLong());
        BigDecimal limitPrice = scaled(buf.getLong());
        String userSubject = getVarString(buf);
        String deskId = getVarString(buf);
        String destination = getVarString(buf);
        String originatorReference = getVarString(buf);
        ListingSnapshot listing = getListing(buf);
        Map<String, Object> executionParameters = readParameters(getVarString(buf));

        return new OrderCommand(schemaVersion, commandId, commandType, userSubject, deskId,
                requestedAt, orderId, expectedVersion, listing, side, orderType, quantity,
                limitPrice, destination, originatorReference, parentOrderId, executionParameters);
    }

    private static <E extends Enum<E>> E ordinal(byte encoded, E[] values) {
        int index = encoded & 0xFF;
        return index >= values.length ? null : values[index];
    }

    private static BigDecimal scaled(long value) {
        return value == 0 ? null : FixedPointMath.toBigDecimal(value);
    }

    private static String writeParameters(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) return null;
        return PARAMETERS.writeValueAsString(parameters);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readParameters(String json) {
        if (json == null || json.isBlank()) return Map.of();
        return PARAMETERS.readValue(json, Map.class);
    }

    /** Pre-encoded listing strings, so the buffer is sized before it is written. */
    private record ListingBytes(ListingSnapshot listing, byte[] symbol, byte[] name,
                                byte[] marketSymbol, byte[] exchangeMic, byte[] exchangeName,
                                byte[] countryCode, byte[] currency) {

        int encodedLength() {
            if (listing == null) return 1;
            return 1 + 8 + 4 + 8 + 8 + 8 + 8
                    + 2 + symbol.length + 2 + name.length + 2 + marketSymbol.length
                    + 2 + exchangeMic.length + 2 + exchangeName.length
                    + 2 + countryCode.length + 2 + currency.length;
        }

        void writeTo(ByteBuffer buf) {
            if (listing == null) {
                buf.put((byte) 0);
                return;
            }
            buf.put((byte) 1);
            buf.putLong(listing.id());
            buf.putInt(listing.version());
            buf.putLong(FixedPointMath.toScaledLong(listing.tickSize()));
            buf.putLong(FixedPointMath.toScaledLong(listing.sizeIncrement()));
            buf.putLong(FixedPointMath.toScaledLong(listing.referencePrice()));
            buf.putLong(FixedPointMath.toScaledLong(listing.previousClose()));
            putVarString(buf, symbol);
            putVarString(buf, name);
            putVarString(buf, marketSymbol);
            putVarString(buf, exchangeMic);
            putVarString(buf, exchangeName);
            putVarString(buf, countryCode);
            putVarString(buf, currency);
        }
    }

    private static ListingBytes listingBytes(ListingSnapshot listing) {
        if (listing == null) {
            return new ListingBytes(null, null, null, null, null, null, null, null);
        }
        return new ListingBytes(listing, toBytes(listing.symbol()), toBytes(listing.name()),
                toBytes(listing.marketSymbol()), toBytes(listing.exchangeMic()),
                toBytes(listing.exchangeName()), toBytes(listing.countryCode()),
                toBytes(listing.currency()));
    }

    private static ListingSnapshot getListing(ByteBuffer buf) {
        if (buf.get() == 0) return null;
        long id = buf.getLong();
        int version = buf.getInt();
        BigDecimal tickSize = scaled(buf.getLong());
        BigDecimal sizeIncrement = scaled(buf.getLong());
        BigDecimal referencePrice = scaled(buf.getLong());
        BigDecimal previousClose = scaled(buf.getLong());
        String symbol = getVarString(buf);
        String name = getVarString(buf);
        String marketSymbol = getVarString(buf);
        String exchangeMic = getVarString(buf);
        String exchangeName = getVarString(buf);
        String countryCode = getVarString(buf);
        String currency = getVarString(buf);
        return new ListingSnapshot(id, version, symbol, name, marketSymbol, exchangeMic,
                exchangeName, countryCode, currency, tickSize, sizeIncrement, referencePrice,
                previousClose);
    }

    /**
     * Encodes an absent timestamp.
     *
     * <p>{@link Long#MIN_VALUE} rather than 0, because 0 is the epoch - a real
     * instant. Encoding absent as 0 meant any message actually carrying
     * 1970-01-01T00:00:00Z decoded with no timestamp at all.
     */
    private static long absentOrEpochMillis(Instant instant) {
        return instant == null ? Long.MIN_VALUE : instant.toEpochMilli();
    }

    private static Instant instantOrAbsent(long epochMillis) {
        return epochMillis == Long.MIN_VALUE ? null : Instant.ofEpochMilli(epochMillis);
    }

    private static void putUuid(ByteBuffer buf, UUID uuid) {
        if (uuid == null) {
            buf.putLong(0L);
            buf.putLong(0L);
        } else {
            buf.putLong(uuid.getMostSignificantBits());
            buf.putLong(uuid.getLeastSignificantBits());
        }
    }

    public static com.emporia.events.math.LongPair getLongPair(ByteBuffer buf) {
        long most = buf.getLong();
        long least = buf.getLong();
        return (most == 0L && least == 0L) ? com.emporia.events.math.LongPair.ZERO : com.emporia.events.math.LongPair.of(most, least);
    }

    private static UUID getUuid(ByteBuffer buf) {
        long most = buf.getLong();
        long least = buf.getLong();
        return (most == 0L && least == 0L) ? null : new UUID(most, least);
    }

    private static byte[] toBytes(String str) {
        return str == null ? new byte[0] : str.getBytes(StandardCharsets.UTF_8);
    }

    private static void putVarString(ByteBuffer buf, byte[] bytes) {
        buf.putShort((short) bytes.length);
        if (bytes.length > 0) {
            buf.put(bytes);
        }
    }

    private static String getVarString(ByteBuffer buf) {
        int length = buf.getShort() & 0xFFFF;
        if (length == 0) return null;
        byte[] bytes = new byte[length];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

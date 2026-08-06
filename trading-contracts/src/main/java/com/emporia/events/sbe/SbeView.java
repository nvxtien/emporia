package com.emporia.events.sbe;

import com.emporia.events.TradingEvents.ExecutionCommand;
import com.emporia.events.TradingEvents.ExecutionCommandType;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.math.FixedPointMath;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Zero-copy, zero-allocation flyweight view over a raw SBE message buffer.
 *
 * <h2>Allocation budget</h2>
 * <table>
 *   <caption>Per-message allocation comparison</caption>
 *   <tr><th>Path</th><th>Heap objects</th><th>Bytes (approx)</th></tr>
 *   <tr><td>{@code SbeEncoderDecoder.decode*()} (old)</td>
 *       <td>9–10</td><td>~600</td></tr>
 *   <tr><td>{@code SbeView} construction (this class)</td>
 *       <td>1 (the {@code SbeView} itself)</td><td>~120</td></tr>
 *   <tr><td>Hot-path dispatch via {@link #eventType()}.{@link AsciiView#equalsAscii}</td>
 *       <td>0</td><td>0</td></tr>
 *   <tr><td>{@link #toOrderDomainEvent()} / {@link #toExecutionCommand()} (escape hatch)</td>
 *       <td>~4–5 (domain record + Strings)</td><td>~300</td></tr>
 * </table>
 *
 * <h2>Implementation notes</h2>
 * <ul>
 *   <li><b>No {@code ByteBuffer}</b> — the constructor reads all fields directly from
 *       the {@code byte[]} via {@link ByteArrayReader} ({@code VarHandle}-backed, big-endian).
 *       {@code ByteBuffer.wrap()} was the only heap allocation on the decode path before this
 *       class existed; it is now entirely absent.</li>
 *   <li><b>Flat int fields for var-field metadata</b> — rather than {@code int[]} arrays for
 *       offsets/lengths (two array header allocations plus pointer indirection), the four
 *       offset and four length values are stored as plain {@code int} instance fields.</li>
 *   <li><b>Lazy {@link AsciiView} wrappers</b> — still stored in a 4-element array because
 *       the number of views per message is small, fixed, and known at construction time.
 *       The array itself is one allocation shared across all four fields of a message.</li>
 *   <li><b>UUID as long pair</b> — UUIDs never leave this class as {@code UUID} objects
 *       unless the caller explicitly calls {@code toUUID*()}.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>Not thread-safe. Each Kafka listener thread holds its own view.
 * The backing buffer must not be mutated while the view is live.
 *
 * <h2>Hot-path consumer pattern</h2>
 * <pre>{@code
 * SbeView view = SbeView.ofOrderDomainEvent(rawBytes);
 *
 * // Zero allocation: pure byte comparison on the buffer
 * if (view.eventType().equalsAscii("CREATED")) {
 *     handleCreated(view.orderIdHi(), view.orderIdLo(),
 *                   view.quantityScaled(), view.priceScaled());
 * } else if (view.eventType().equalsAscii("CANCEL_REQUESTED")) {
 *     handleCancel(view.orderIdHi(), view.orderIdLo());
 * }
 *
 * // Only materialise when a domain record must cross a service boundary
 * OrderDomainEvent record = view.toOrderDomainEvent();
 * }</pre>
 */
public final class SbeView {

    // ── Message type constants ────────────────────────────────────────────────
    public static final int TYPE_ORDER_DOMAIN_EVENT = SbeEncoderDecoder.MSG_TYPE_ORDER_DOMAIN_EVENT;
    public static final int TYPE_EXECUTION_COMMAND  = SbeEncoderDecoder.MSG_TYPE_EXECUTION_COMMAND;

    // ── Wire layout — OrderDomainEvent ────────────────────────────────────────
    //  [0..3]   magic          int
    //  [4..5]   msgType        short
    //  [6..7]   schemaVersion  short
    //  [8..23]  eventId        UUID (2 longs)
    //  [24..39] commandId      UUID (2 longs)
    //  [40..55] orderId        UUID (2 longs)
    //  [56..63] orderVersion   long
    //  [64]     status         byte
    //  [65..72] occurredAt     long (epoch-millis)
    //  [73..]   var×4          short-length-prefixed UTF-8 strings
    //           userSubject, deskId, eventType, payload
    private static final int ODE_OFF_UUID0         = 8;
    private static final int ODE_OFF_UUID1         = 24;
    private static final int ODE_OFF_UUID2         = 40;
    private static final int ODE_OFF_ORDER_VER     = 56;
    private static final int ODE_OFF_STATUS        = 64;
    private static final int ODE_OFF_OCCURRED_AT   = 65;
    private static final int ODE_OFF_VARS          = 73;

    // ── Wire layout — ExecutionCommand ────────────────────────────────────────
    //  [0..3]   magic          int
    //  [4..5]   msgType        short
    //  [6..7]   schemaVersion  short
    //  [8..23]  commandId      UUID (2 longs)
    //  [24..39] orderId        UUID (2 longs)
    //  [40]     commandType    byte
    //  [41..48] occurredAt     long (epoch-millis)
    //  [49..56] quantity       long (fixed-point scale 6)
    //  [57..64] price          long (fixed-point scale 6)
    //  [65..]   var×4          short-length-prefixed UTF-8 strings
    //           deskId, executionReference, venue, detail
    private static final int EC_OFF_UUID0          = 8;
    private static final int EC_OFF_UUID1          = 24;
    private static final int EC_OFF_CMD_TYPE       = 40;
    private static final int EC_OFF_OCCURRED_AT    = 41;
    private static final int EC_OFF_QTY            = 49;
    private static final int EC_OFF_PRICE          = 57;
    private static final int EC_OFF_VARS           = 65;

    // ── Var-field slot indices ────────────────────────────────────────────────
    // ODE: [0]=userSubject  [1]=deskId  [2]=eventType  [3]=payload
    // EC:  [0]=deskId       [1]=execRef [2]=venue      [3]=detail
    private static final int IDX_0 = 0;
    private static final int IDX_1 = 1;
    private static final int IDX_2 = 2;
    private static final int IDX_3 = 3;

    // ── Instance state ────────────────────────────────────────────────────────

    private final byte[] buffer;
    private final int    msgType;
    private final int    schemaVersion;

    // Fixed-width primitives — read once at construction, stored on the JVM stack
    // as final fields (hotspot will scalar-replace them in the common inline path).
    private final long uuid0Hi, uuid0Lo;
    private final long uuid1Hi, uuid1Lo;
    private final long uuid2Hi, uuid2Lo;  // ODE: orderId; EC: 0/0
    private final long orderVersion;      // ODE only; EC: 0
    private final int  statusOrCmdType;
    private final long occurredAtMillis;
    private final long quantityScaled;    // EC only; ODE: 0
    private final long priceScaled;       // EC only; ODE: 0

    // Var-field offsets and lengths — flat ints, no array allocation.
    // Naming: v<slot>Off = byte offset of data start (after the 2-byte length prefix)
    //         v<slot>Len = byte length of the data region
    private final int v0Off, v0Len;
    private final int v1Off, v1Len;
    private final int v2Off, v2Len;
    private final int v3Off, v3Len;

    // Lazily created AsciiView wrappers — one array shared across four slots.
    // Allocated once per SbeView; each element materialised on first access.
    private final AsciiView[] varViews = new AsciiView[4];

    // ── Factory methods ───────────────────────────────────────────────────────

    /**
     * Wraps an {@code OrderDomainEvent} SBE payload zero-copy.
     *
     * @throws IllegalArgumentException if {@code buffer} is not a valid ODE payload
     */
    public static SbeView ofOrderDomainEvent(byte[] buffer) {
        return new SbeView(buffer, SbeEncoderDecoder.MSG_TYPE_ORDER_DOMAIN_EVENT);
    }

    /**
     * Wraps an {@code ExecutionCommand} SBE payload zero-copy.
     *
     * @throws IllegalArgumentException if {@code buffer} is not a valid EC payload
     */
    public static SbeView ofExecutionCommand(byte[] buffer) {
        return new SbeView(buffer, SbeEncoderDecoder.MSG_TYPE_EXECUTION_COMMAND);
    }

    // ── Constructor — no ByteBuffer allocation ────────────────────────────────

    private SbeView(byte[] buffer, short expectedMsgType) {
        // Magic check: read 4 bytes directly via VarHandle — no ByteBuffer.wrap()
        if (buffer == null || buffer.length < 6
                || ByteArrayReader.getInt(buffer, 0) != SbeEncoderDecoder.MAGIC_HEADER) {
            throw new IllegalArgumentException("Not an SBE payload");
        }
        short actualMsgType = ByteArrayReader.getShort(buffer, 4);
        if (actualMsgType != expectedMsgType) {
            throw new IllegalArgumentException(
                    "Expected msgType " + expectedMsgType + " but got " + actualMsgType);
        }

        this.buffer        = buffer;
        this.msgType       = actualMsgType & 0xFFFF;
        this.schemaVersion = ByteArrayReader.getUnsignedShort(buffer, 6);

        if (expectedMsgType == SbeEncoderDecoder.MSG_TYPE_ORDER_DOMAIN_EVENT) {
            uuid0Hi = ByteArrayReader.getLong(buffer, ODE_OFF_UUID0);
            uuid0Lo = ByteArrayReader.getLong(buffer, ODE_OFF_UUID0 + 8);
            uuid1Hi = ByteArrayReader.getLong(buffer, ODE_OFF_UUID1);
            uuid1Lo = ByteArrayReader.getLong(buffer, ODE_OFF_UUID1 + 8);
            uuid2Hi = ByteArrayReader.getLong(buffer, ODE_OFF_UUID2);
            uuid2Lo = ByteArrayReader.getLong(buffer, ODE_OFF_UUID2 + 8);
            orderVersion     = ByteArrayReader.getLong(buffer, ODE_OFF_ORDER_VER);
            statusOrCmdType  = ByteArrayReader.getUnsignedByte(buffer, ODE_OFF_STATUS);
            occurredAtMillis = ByteArrayReader.getLong(buffer, ODE_OFF_OCCURRED_AT);
            quantityScaled   = 0L;
            priceScaled      = 0L;
            // Scan var fields starting at ODE_OFF_VARS
            int pos = ODE_OFF_VARS;
            v0Len = ByteArrayReader.getUnsignedShort(buffer, pos); v0Off = pos + 2; pos += 2 + v0Len;
            v1Len = ByteArrayReader.getUnsignedShort(buffer, pos); v1Off = pos + 2; pos += 2 + v1Len;
            v2Len = ByteArrayReader.getUnsignedShort(buffer, pos); v2Off = pos + 2; pos += 2 + v2Len;
            v3Len = ByteArrayReader.getUnsignedShort(buffer, pos); v3Off = pos + 2;
        } else { // EXECUTION_COMMAND
            uuid0Hi = ByteArrayReader.getLong(buffer, EC_OFF_UUID0);
            uuid0Lo = ByteArrayReader.getLong(buffer, EC_OFF_UUID0 + 8);
            uuid1Hi = ByteArrayReader.getLong(buffer, EC_OFF_UUID1);
            uuid1Lo = ByteArrayReader.getLong(buffer, EC_OFF_UUID1 + 8);
            uuid2Hi = 0L; uuid2Lo = 0L;
            orderVersion     = 0L;
            statusOrCmdType  = ByteArrayReader.getUnsignedByte(buffer, EC_OFF_CMD_TYPE);
            occurredAtMillis = ByteArrayReader.getLong(buffer, EC_OFF_OCCURRED_AT);
            quantityScaled   = ByteArrayReader.getLong(buffer, EC_OFF_QTY);
            priceScaled      = ByteArrayReader.getLong(buffer, EC_OFF_PRICE);
            // Scan var fields starting at EC_OFF_VARS
            int pos = EC_OFF_VARS;
            v0Len = ByteArrayReader.getUnsignedShort(buffer, pos); v0Off = pos + 2; pos += 2 + v0Len;
            v1Len = ByteArrayReader.getUnsignedShort(buffer, pos); v1Off = pos + 2; pos += 2 + v1Len;
            v2Len = ByteArrayReader.getUnsignedShort(buffer, pos); v2Off = pos + 2; pos += 2 + v2Len;
            v3Len = ByteArrayReader.getUnsignedShort(buffer, pos); v3Off = pos + 2;
        }
    }

    // ── Type / schema ─────────────────────────────────────────────────────────

    public int msgType()       { return msgType; }
    public int schemaVersion() { return schemaVersion; }

    // ── UUID as raw long pairs — zero allocation ──────────────────────────────
    //
    // Hot-path identity checks never need a UUID object:
    //
    //   if (view.uuid0Hi() == knownId.getMostSignificantBits()
    //    && view.uuid0Lo() == knownId.getLeastSignificantBits()) { ... }
    //
    // Call toUUID*() only when a UUID object must cross a service boundary.

    /** Most-significant bits of slot 0 (ODE: eventId; EC: commandId). */
    public long uuid0Hi() { return uuid0Hi; }
    /** Least-significant bits of slot 0 (ODE: eventId; EC: commandId). */
    public long uuid0Lo() { return uuid0Lo; }
    /** Most-significant bits of slot 1 (ODE: commandId; EC: orderId). */
    public long uuid1Hi() { return uuid1Hi; }
    /** Least-significant bits of slot 1 (ODE: commandId; EC: orderId). */
    public long uuid1Lo() { return uuid1Lo; }
    /** Most-significant bits of slot 2 (ODE: orderId; EC: always 0). */
    public long uuid2Hi() { return uuid2Hi; }
    /** Least-significant bits of slot 2 (ODE: orderId; EC: always 0). */
    public long uuid2Lo() { return uuid2Lo; }

    // ODE named aliases
    /** ODE: {@code eventId} MSB. */   public long eventIdHi()   { return uuid0Hi; }
    /** ODE: {@code eventId} LSB. */   public long eventIdLo()   { return uuid0Lo; }
    /** ODE: {@code commandId} MSB. */ public long commandIdHi() { return uuid1Hi; }
    /** ODE: {@code commandId} LSB. */ public long commandIdLo() { return uuid1Lo; }
    /** ODE: {@code orderId} MSB. */   public long orderIdHi()   { return uuid2Hi; }
    /** ODE: {@code orderId} LSB. */   public long orderIdLo()   { return uuid2Lo; }

    // EC named aliases (slot mapping differs from ODE)
    /** EC: {@code commandId} MSB. */ public long ecCommandIdHi() { return uuid0Hi; }
    /** EC: {@code commandId} LSB. */ public long ecCommandIdLo() { return uuid0Lo; }
    /** EC: {@code orderId} MSB. */   public long ecOrderIdHi()   { return uuid1Hi; }
    /** EC: {@code orderId} LSB. */   public long ecOrderIdLo()   { return uuid1Lo; }

    /** Zero-allocation equality for UUID slot 0. */
    public boolean uuid0Equals(long hi, long lo) { return uuid0Hi == hi && uuid0Lo == lo; }
    /** Zero-allocation equality for UUID slot 1. */
    public boolean uuid1Equals(long hi, long lo) { return uuid1Hi == hi && uuid1Lo == lo; }
    /** Zero-allocation equality for UUID slot 2. */
    public boolean uuid2Equals(long hi, long lo) { return uuid2Hi == hi && uuid2Lo == lo; }

    // ── LongPair representations ─────────────────────────────────────────────

    public com.emporia.events.math.LongPair uuid0Pair() { return com.emporia.events.math.LongPair.of(uuid0Hi, uuid0Lo); }
    public com.emporia.events.math.LongPair uuid1Pair() { return com.emporia.events.math.LongPair.of(uuid1Hi, uuid1Lo); }
    public com.emporia.events.math.LongPair uuid2Pair() { return com.emporia.events.math.LongPair.of(uuid2Hi, uuid2Lo); }

    public com.emporia.events.math.LongPair eventIdPair()   { return uuid0Pair(); }
    public com.emporia.events.math.LongPair commandIdPair() { return uuid1Pair(); }
    public com.emporia.events.math.LongPair orderIdPair()   { return uuid2Pair(); }

    public com.emporia.events.math.LongPair ecCommandIdPair() { return uuid0Pair(); }
    public com.emporia.events.math.LongPair ecOrderIdPair()   { return uuid1Pair(); }

    // ── UUID materialisation — allocates one UUID; call only at boundaries ────

    /** ODE: {@code eventId}. EC: {@code commandId}. Allocates one {@link UUID}. */
    public UUID toUUID0() { return toUuid(uuid0Hi, uuid0Lo); }
    /** ODE: {@code commandId}. EC: {@code orderId}. Allocates one {@link UUID}. */
    public UUID toUUID1() { return toUuid(uuid1Hi, uuid1Lo); }
    /** ODE: {@code orderId}. EC: {@code null}. Allocates one {@link UUID} or returns null. */
    public UUID toUUID2() { return (uuid2Hi == 0L && uuid2Lo == 0L) ? null : toUuid(uuid2Hi, uuid2Lo); }

    // ── Primitive fixed-width fields — zero allocation ────────────────────────

    /** ODE: order version for optimistic locking. EC: always {@code 0}. */
    public long orderVersion() { return orderVersion; }

    /** Raw ordinal — ODE: {@link OrderStatus}; EC: {@link ExecutionCommandType}. */
    public int statusOrCmdTypeOrdinal() { return statusOrCmdType; }

    /** ODE: decoded {@link OrderStatus}. EC: throws. */
    public OrderStatus orderStatus() {
        if (msgType != TYPE_ORDER_DOMAIN_EVENT) {
            throw new IllegalStateException("orderStatus() is only valid for OrderDomainEvent views");
        }
        return OrderStatus.values()[Math.min(statusOrCmdType, OrderStatus.values().length - 1)];
    }

    /** EC: decoded {@link ExecutionCommandType}. ODE: throws. */
    public ExecutionCommandType executionCommandType() {
        if (msgType != TYPE_EXECUTION_COMMAND) {
            throw new IllegalStateException("executionCommandType() is only valid for ExecutionCommand views");
        }
        return ExecutionCommandType.values()[Math.min(statusOrCmdType, ExecutionCommandType.values().length - 1)];
    }

    /** Raw epoch-millis. Zero allocation — prefer over {@link #occurredAt()}. */
    public long occurredAtMillis() { return occurredAtMillis; }

    /** Allocates one {@link Instant}. Use {@link #occurredAtMillis()} on hot paths. */
    public Instant occurredAt() { return occurredAtMillis == 0L ? null : Instant.ofEpochMilli(occurredAtMillis); }

    /** EC: quantity fixed-point long (scale 6). ODE: {@code 0}. Zero allocation. */
    public long quantityScaled() { return quantityScaled; }

    /** EC: price fixed-point long (scale 6). ODE: {@code 0}. Zero allocation. */
    public long priceScaled() { return priceScaled; }

    // ── Variable-length fields — zero-copy AsciiView ──────────────────────────

    /**
     * ODE: {@code userSubject}. Zero-copy {@link AsciiView} over the buffer.
     * Lazily created and cached.
     */
    public AsciiView userSubject() {
        requireType(TYPE_ORDER_DOMAIN_EVENT, "userSubject");
        return varView(IDX_0);
    }

    /**
     * ODE: {@code deskId} (slot 1).
     * EC:  {@code deskId} (slot 0).
     * Type-aware dispatch; zero allocation.
     */
    public AsciiView deskId() {
        return varView(msgType == TYPE_EXECUTION_COMMAND ? IDX_0 : IDX_1);
    }

    /**
     * ODE: {@code eventType} — the hot-path dispatch key.
     * Use {@link AsciiView#equalsAscii(String)} to avoid String allocation in switch logic.
     */
    public AsciiView eventType() {
        requireType(TYPE_ORDER_DOMAIN_EVENT, "eventType");
        return varView(IDX_2);
    }

    /**
     * ODE: {@code payload} (JSON order snapshot). Usually the largest field;
     * only needed when the full order state must be deserialized.
     */
    public AsciiView payload() {
        requireType(TYPE_ORDER_DOMAIN_EVENT, "payload");
        return varView(IDX_3);
    }

    /** EC: {@code executionReference}. */
    public AsciiView executionReference() {
        requireType(TYPE_EXECUTION_COMMAND, "executionReference");
        return varView(IDX_1);
    }

    /** EC: venue MIC (e.g. {@code "XNAS"}). */
    public AsciiView venue() {
        requireType(TYPE_EXECUTION_COMMAND, "venue");
        return varView(IDX_2);
    }

    /** EC: human-readable detail / rejection reason. */
    public AsciiView detail() {
        requireType(TYPE_EXECUTION_COMMAND, "detail");
        return varView(IDX_3);
    }

    // ── Materialisation — crosses service boundaries ──────────────────────────

    /**
     * Materialises a full {@link OrderDomainEvent} domain record.
     *
     * <p>Call only when the event must reach a component that requires a domain record
     * (Kafka publisher, JPA handler). Check {@link #eventType()} first to avoid
     * materialising events the consumer discards.
     */
    public OrderDomainEvent toOrderDomainEvent() {
        requireType(TYPE_ORDER_DOMAIN_EVENT, "toOrderDomainEvent");
        return new OrderDomainEvent(
                schemaVersion,
                toUUID0(),                           // eventId
                toUUID1(),                           // commandId
                toUUID2(),                           // orderId
                varView(IDX_0).toString(),           // userSubject
                varView(IDX_1).toString(),           // deskId
                varView(IDX_2).toString(),           // eventType
                orderVersion,
                orderStatus(),
                occurredAt(),
                varView(IDX_3).toString()            // payload
        );
    }

    /**
     * Materialises a full {@link ExecutionCommand} domain record.
     */
    public ExecutionCommand toExecutionCommand() {
        requireType(TYPE_EXECUTION_COMMAND, "toExecutionCommand");
        BigDecimal qty   = quantityScaled == 0L ? null : FixedPointMath.toBigDecimal(quantityScaled);
        BigDecimal price = priceScaled    == 0L ? null : FixedPointMath.toBigDecimal(priceScaled);
        return new ExecutionCommand(
                schemaVersion,
                toUUID0(),                           // commandId
                executionCommandType(),
                toUUID1(),                           // orderId
                varView(IDX_0).toString(),           // deskId
                varView(IDX_1).toString(),           // executionReference
                qty,
                price,
                varView(IDX_2).toString(),           // venue
                occurredAt(),
                varView(IDX_3).toString()            // detail
        );
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private AsciiView varView(int index) {
        AsciiView v = varViews[index];
        if (v != null) return v;
        int off, len;
        switch (index) {
            case IDX_0 -> { off = v0Off; len = v0Len; }
            case IDX_1 -> { off = v1Off; len = v1Len; }
            case IDX_2 -> { off = v2Off; len = v2Len; }
            default    -> { off = v3Off; len = v3Len; }
        }
        v = len == 0 ? AsciiView.EMPTY : new AsciiView(buffer, off, len);
        varViews[index] = v;
        return v;
    }

    private static UUID toUuid(long hi, long lo) {
        return (hi == 0L && lo == 0L) ? null : new UUID(hi, lo);
    }

    private void requireType(int required, String field) {
        if (msgType != required) {
            throw new IllegalStateException(field + "() is only valid for msgType " + required
                    + " views (this is msgType " + msgType + ")");
        }
    }
}

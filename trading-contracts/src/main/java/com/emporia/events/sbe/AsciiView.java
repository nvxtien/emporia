package com.emporia.events.sbe;

import java.nio.charset.StandardCharsets;

/**
 * Zero-allocation, zero-copy flyweight view over a UTF-8 / ASCII byte region.
 *
 * <h2>Purpose</h2>
 * <p>Every SBE message decoded from Kafka today allocates a fresh {@code byte[]} and
 * a {@code String} per variable-length field — two heap objects and a full UTF-8 decode
 * that neither the Kafka consumer nor the domain handler may ever need as a {@code String}.
 * {@code AsciiView} wraps the <em>original</em> message buffer in-place: no copy, no
 * decode, no allocation beyond the flyweight object itself (~16 bytes on-heap for the
 * three fields below on a modern JVM).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>The backing {@code buffer} must not be mutated while this view is live.
 *       SBE messages are immutable byte arrays returned from the serializer, so this
 *       holds without any synchronisation.</li>
 *   <li>{@link #toString()} materializes a {@code String} exactly once (lazy, cached).
 *       Repeated calls return the same instance — safe for logging, JSON serialization,
 *       and any code that demands a {@code String}.</li>
 *   <li>{@link CharSequence} methods ({@code length}, {@code charAt}, {@code subSequence})
 *       operate directly on the buffer bytes without materializing a {@code String}.
 *       They are sufficient for equality checks, hashing, and pattern matching on
 *       ASCII content (order event types, venue MICs, desk IDs).</li>
 *   <li>{@code equals} and {@code hashCode} are content-based and consistent with
 *       {@code String.equals} / {@code String.hashCode} when the region contains
 *       single-byte ASCII. Do <em>not</em> rely on them for multi-byte Unicode
 *       sequences — use {@link #toString()} in that case.</li>
 * </ul>
 *
 * <h2>Typical usage in a hot-path consumer</h2>
 * <pre>{@code
 * AsciiView eventType = decoded.eventTypeView();
 * if (eventType.equalsAscii("CREATED")) {          // zero allocation
 *     handleCreated(decoded);
 * } else if (eventType.equalsAscii("CANCEL_REQUESTED")) {
 *     handleCancelRequested(decoded);
 * }
 * // Only materialise String if it must cross a service boundary
 * String forLog = eventType.toString();             // allocated once, cached
 * }</pre>
 *
 * <h2>Null / empty sentinel</h2>
 * <p>A {@code length == 0} region encodes a logically-null or empty string (matching
 * the SBE wire format). {@link #isPresent()} distinguishes "empty" from "null" only
 * at the wire level; both materialise as an empty {@code String} via {@link #toString()}.
 */
public final class AsciiView implements CharSequence {

    /** Shared sentinel returned for zero-length / null wire values. */
    public static final AsciiView EMPTY = new AsciiView(new byte[0], 0, 0);

    private final byte[] buffer;
    private final int offset;
    private final int length;

    /** Lazily materialized String — allocated at most once per instance. */
    private String string;

    /**
     * Wraps a region of {@code buffer[offset..offset+length)}.
     *
     * @param buffer the backing byte array (must not be mutated after construction)
     * @param offset start index within {@code buffer}, inclusive
     * @param length number of bytes in the region
     */
    public AsciiView(byte[] buffer, int offset, int length) {
        if (buffer == null) throw new IllegalArgumentException("buffer must not be null");
        if (offset < 0 || length < 0 || offset + length > buffer.length) {
            throw new IllegalArgumentException(
                    "invalid region: offset=" + offset + " length=" + length
                    + " bufferLen=" + buffer.length);
        }
        this.buffer = buffer;
        this.offset = offset;
        this.length = length;
    }

    /** Returns {@code true} if the region is non-empty (length &gt; 0). */
    public boolean isPresent() {
        return length > 0;
    }

    /**
     * Zero-allocation equality check against a compile-time ASCII literal.
     *
     * <p>Compares byte-by-byte without allocating any intermediate object.
     * Correct for strings whose characters are all in the US-ASCII range (0x00–0x7F),
     * which covers all fixed vocabulary in the SBE schema: event types
     * ({@code "CREATED"}, {@code "CANCEL_REQUESTED"}, …), venue MICs
     * ({@code "XNAS"}, {@code "XNYS"}, …), order destinations ({@code "DMA"},
     * {@code "SMART"}, {@code "VWAP"}), and desk / originator IDs.
     *
     * @param ascii the ASCII string to compare against (must be pure ASCII)
     * @return {@code true} if this view's bytes equal {@code ascii} encoded in UTF-8
     */
    public boolean equalsAscii(String ascii) {
        if (ascii == null) return length == 0;
        if (ascii.length() != length) return false;
        for (int i = 0; i < length; i++) {
            if (buffer[offset + i] != (byte) ascii.charAt(i)) return false;
        }
        return true;
    }

    /**
     * Zero-allocation case-insensitive equality for ASCII content.
     *
     * <p>Uses the standard ASCII fold ({@code | 0x20}) so it is only correct for
     * pure ASCII input — sufficient for all fixed-vocabulary fields in the schema.
     */
    public boolean equalsAsciiIgnoreCase(String ascii) {
        if (ascii == null) return length == 0;
        if (ascii.length() != length) return false;
        for (int i = 0; i < length; i++) {
            int b = buffer[offset + i] & 0xFF;
            int c = ascii.charAt(i) & 0xFF;
            // ASCII fold: lower = upper | 0x20 for letters A-Z / a-z
            if (b != c && (b | 0x20) != (c | 0x20)) return false;
        }
        return true;
    }

    // ── CharSequence ─────────────────────────────────────────────────────────

    @Override
    public int length() {
        return length;
    }

    /**
     * Returns the char at {@code index}, interpreting the byte as an unsigned value.
     * Correct for US-ASCII content; multi-byte UTF-8 sequences will return the raw
     * lead/continuation byte rather than the decoded code point.
     */
    @Override
    public char charAt(int index) {
        if (index < 0 || index >= length) throw new IndexOutOfBoundsException("index: " + index);
        return (char) (buffer[offset + index] & 0xFF);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        if (start < 0 || end > length || start > end) {
            throw new IndexOutOfBoundsException("start=" + start + " end=" + end + " length=" + length);
        }
        if (start == end) return EMPTY;
        return new AsciiView(buffer, offset + start, end - start);
    }

    // ── Object ────────────────────────────────────────────────────────────────

    /**
     * Materializes the underlying bytes as a {@code String} using UTF-8 decoding.
     * The result is cached; repeated calls return the same instance.
     */
    @Override
    public String toString() {
        if (string == null) {
            string = length == 0 ? "" : new String(buffer, offset, length, StandardCharsets.UTF_8);
        }
        return string;
    }

    /**
     * Content-based equality. Consistent with {@link String#equals} for pure ASCII
     * content. Compares against another {@link AsciiView} byte-by-byte (zero copy)
     * or falls back to {@link #toString()} comparison for any other {@link CharSequence}.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other instanceof AsciiView that) {
            if (this.length != that.length) return false;
            for (int i = 0; i < length; i++) {
                if (this.buffer[this.offset + i] != that.buffer[that.offset + i]) return false;
            }
            return true;
        }
        if (other instanceof CharSequence cs) {
            return toString().equals(cs.toString());
        }
        return false;
    }

    /**
     * Hash code consistent with {@link String#hashCode} for ASCII content — i.e.
     * {@code new AsciiView(buf, off, len).hashCode() == toString().hashCode()}.
     *
     * <p>Computed over the raw bytes using the same {@code 31*h + c} formula that
     * {@link String} uses. For pure ASCII this is identical to the String hash because
     * each char value equals the unsigned byte value.
     */
    @Override
    public int hashCode() {
        int h = 0;
        for (int i = 0; i < length; i++) {
            h = 31 * h + (buffer[offset + i] & 0xFF);
        }
        return h;
    }
}

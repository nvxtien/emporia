package com.emporia.execution.fix;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Framing and field encoding for FIXT.1.1, with no session state of its own.
 *
 * <p>Lifted out of {@code FixExecutionVenueGateway}'s inner {@code FixSession},
 * where it worked but could only ever serve the initiator side. Nothing here
 * depends on which end of the connection Emporia is: the wire format is the same
 * whether a message goes to a venue Emporia dialled or to a counterparty that
 * dialled Emporia.
 *
 * <p>This is the first and least dangerous piece of the session-layer split.
 * Everything in it is static and stateless, so extracting it cannot change
 * behaviour - and the check is that the existing gateway tests pass unchanged.
 */
public final class FixCodec {

    /** FIX field separator. */
    public static final byte SOH = 1;

    /** {@code BeginString} the venue gateway speaks, and an acceptor must match. */
    public static final String BEGIN_STRING = "FIXT.1.1";

    private static final DateTimeFormatter FIX_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private FixCodec() {
    }

    /** Renders {@code sendingTime} in the FIX {@code UTCTimestamp} format. */
    public static String timestamp(Instant sendingTime) {
        return FIX_TIME.format(sendingTime);
    }

    /**
     * Builds a complete message: standard header, the caller's fields, and a
     * trailer whose checksum covers everything before it.
     */
    public static byte[] encode(String messageType, int seqNum, Instant sendingTime,
                                String senderCompId, String targetCompId,
                                LinkedHashMap<Integer, String> fields) {
        StringBuilder body = new StringBuilder();
        append(body, 35, messageType);
        append(body, 34, String.valueOf(seqNum));
        append(body, 49, senderCompId);
        append(body, 56, targetCompId);
        append(body, 52, timestamp(sendingTime));
        fields.forEach((tag, value) -> append(body, tag, value));
        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.US_ASCII);
        String header = "8=" + BEGIN_STRING + "\u0001" + "9=" + bodyBytes.length + "\u0001";
        byte[] withoutChecksum = (header + body).getBytes(StandardCharsets.US_ASCII);
        int checksum = 0;
        for (byte value : withoutChecksum) checksum = (checksum + Byte.toUnsignedInt(value)) % 256;
        String trailer = "10=%03d\u0001".formatted(checksum);
        byte[] result = Arrays.copyOf(withoutChecksum, withoutChecksum.length + trailer.length());
        System.arraycopy(trailer.getBytes(StandardCharsets.US_ASCII), 0, result, withoutChecksum.length,
                trailer.length());
        return result;
    }

    /**
     * Index one past the end of the first complete message in {@code bytes}, or
     * {@code -1} if the checksum field has not arrived yet.
     *
     * <p>Matches {@code 10=nnn} only at a field boundary, so a payload that
     * happens to contain those bytes cannot truncate a message early.
     */
    public static int messageBoundary(byte[] bytes) {
        for (int index = 0; index <= bytes.length - 7; index++) {
            if ((index == 0 || bytes[index - 1] == SOH)
                    && bytes[index] == '1' && bytes[index + 1] == '0' && bytes[index + 2] == '='
                    && bytes[index + 6] == SOH) {
                return index + 7;
            }
        }
        return -1;
    }

    /** Splits a complete message into tag/value pairs, preserving wire order. */
    public static Map<Integer, String> parse(byte[] message) {
        Map<Integer, String> fields = new LinkedHashMap<>();
        for (String field : new String(message, StandardCharsets.US_ASCII).split("\\u0001")) {
            int separator = field.indexOf('=');
            if (separator > 0) fields.put(Integer.parseInt(field.substring(0, separator)),
                    field.substring(separator + 1));
        }
        return fields;
    }

    /** Builds an ordered field map from alternating tag and value arguments. */
    public static LinkedHashMap<Integer, String> fields(Object... values) {
        LinkedHashMap<Integer, String> fields = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            fields.put((Integer) values[index], (String) values[index + 1]);
        }
        return fields;
    }

    /**
     * Reads a numeric tag, falling back rather than throwing.
     *
     * <p>A counterparty that sends a malformed sequence number must not take the
     * session down. That matters more on the acceptor side, where the messages
     * come from somebody Emporia did not choose.
     */
    public static int integer(Map<Integer, String> fields, int tag, int defaultValue) {
        String value = fields.get(tag);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException malformed) {
            return defaultValue;
        }
    }

    /** As {@link #integer}, for a value already extracted, with no default. */
    public static Integer integerOrNull(String value) {
        if (value == null) return null;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static void append(StringBuilder target, int tag, String value) {
        target.append(tag).append('=').append(value).append((char) SOH);
    }
}

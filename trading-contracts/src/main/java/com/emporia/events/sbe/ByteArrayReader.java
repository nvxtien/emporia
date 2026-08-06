package com.emporia.events.sbe;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Zero-allocation, direct {@code byte[]} reader using {@link VarHandle} with
 * big-endian byte order.
 *
 * <h2>Why not {@code ByteBuffer.wrap()}?</h2>
 * <p>{@code ByteBuffer.wrap(byte[])} allocates a {@code HeapByteBuffer} object on
 * every call — roughly 48 bytes of heap garbage per message on a modern JVM. On
 * the Kafka hot path this happens once per {@link SbeView} construction: small, but
 * unnecessary because all fields in the SBE wire format sit at statically-known or
 * cheaply-computed offsets that can be read with a single {@code VarHandle.get}.
 *
 * <h2>VarHandle vs Unsafe</h2>
 * <p>{@code VarHandle} is the official, module-safe replacement for
 * {@code sun.misc.Unsafe} array reads. It is available since Java 9, supported
 * on all JVM implementations, and JIT-compiled to the same native instruction
 * ({@code movbe} or equivalent) as an {@code Unsafe} call. No special module
 * exports are required.
 *
 * <h2>Byte order</h2>
 * <p>The SBE wire format uses big-endian ({@link ByteOrder#BIG_ENDIAN}) for all
 * multi-byte scalars, matching {@code ByteBuffer}'s default. The {@code VarHandle}
 * is created with {@code withInvokeExactBehavior()} disabled (the default) so that
 * widening conversions apply; explicit casts are used where the return type of
 * {@code int} vs {@code long} matters.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * int  magic   = ByteArrayReader.getInt(buf, 0);
 * short type   = ByteArrayReader.getShort(buf, 4);
 * long epochMs = ByteArrayReader.getLong(buf, 41);
 * byte ordinal = ByteArrayReader.getByte(buf, 40);
 * }</pre>
 *
 * <p>All methods are {@code static} and {@code final}; the JIT inlines them to
 * a single load instruction after the first few hundred calls.
 */
public final class ByteArrayReader {

    private static final VarHandle SHORT_HANDLE =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle INT_HANDLE =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle LONG_HANDLE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    private ByteArrayReader() {
    }

    /**
     * Reads a single byte at {@code offset}. No allocation.
     */
    public static byte getByte(byte[] buf, int offset) {
        return buf[offset];
    }

    /**
     * Reads a big-endian {@code short} from {@code buf[offset..offset+1]}.
     * No allocation.
     */
    public static short getShort(byte[] buf, int offset) {
        return (short) SHORT_HANDLE.get(buf, offset);
    }

    /**
     * Reads a big-endian {@code int} from {@code buf[offset..offset+3]}.
     * No allocation.
     */
    public static int getInt(byte[] buf, int offset) {
        return (int) INT_HANDLE.get(buf, offset);
    }

    /**
     * Reads a big-endian {@code long} from {@code buf[offset..offset+7]}.
     * No allocation.
     */
    public static long getLong(byte[] buf, int offset) {
        return (long) LONG_HANDLE.get(buf, offset);
    }

    /**
     * Returns the unsigned value of a short at {@code offset}, as an {@code int}.
     * Equivalent to {@code getShort(buf, offset) & 0xFFFF} without boxing.
     */
    public static int getUnsignedShort(byte[] buf, int offset) {
        return getShort(buf, offset) & 0xFFFF;
    }

    /**
     * Returns the unsigned value of a byte at {@code offset}, as an {@code int}.
     */
    public static int getUnsignedByte(byte[] buf, int offset) {
        return buf[offset] & 0xFF;
    }
}

package com.emporia.events.sbe;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link ByteArrayReader} produces the same values as
 * {@code ByteBuffer} (big-endian) for all four scalar widths, including
 * reads at non-zero offsets within the array.
 */
class ByteArrayReaderTest {

    // Build a buffer with known values at predictable offsets.
    // Layout (big-endian):
    //  [0]      byte  = 0x7F
    //  [1..2]   short = 0x1234
    //  [3..6]   int   = 0xDEADBEEF
    //  [7..14]  long  = 0x0102030405060708L
    //  [15]     byte  = 0xFF (unsigned = 255)
    //  [16..17] short = -1   (0xFFFF, unsigned = 65535)
    private static final byte[] WIRE;
    static {
        ByteBuffer bb = ByteBuffer.allocate(18).order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) 0x7F);
        bb.putShort((short) 0x1234);
        bb.putInt(0xDEADBEEF);
        bb.putLong(0x0102030405060708L);
        bb.put((byte) 0xFF);
        bb.putShort((short) 0xFFFF);
        WIRE = bb.array();
    }

    @Test
    void getByte_atZero() {
        assertThat(ByteArrayReader.getByte(WIRE, 0)).isEqualTo((byte) 0x7F);
    }

    @Test
    void getShort_bigEndian() {
        assertThat(ByteArrayReader.getShort(WIRE, 1)).isEqualTo((short) 0x1234);
    }

    @Test
    void getInt_bigEndian() {
        assertThat(ByteArrayReader.getInt(WIRE, 3)).isEqualTo(0xDEADBEEF);
    }

    @Test
    void getLong_bigEndian() {
        assertThat(ByteArrayReader.getLong(WIRE, 7)).isEqualTo(0x0102030405060708L);
    }

    @Test
    void getUnsignedByte_maxValue() {
        assertThat(ByteArrayReader.getUnsignedByte(WIRE, 15)).isEqualTo(255);
    }

    @Test
    void getUnsignedShort_maxValue() {
        assertThat(ByteArrayReader.getUnsignedShort(WIRE, 16)).isEqualTo(65535);
    }

    @Test
    void allReadsAgreeWithByteBuffer() {
        ByteBuffer bb = ByteBuffer.wrap(WIRE).order(ByteOrder.BIG_ENDIAN);
        assertThat(ByteArrayReader.getByte(WIRE, 0)).isEqualTo(bb.get(0));
        assertThat(ByteArrayReader.getShort(WIRE, 1)).isEqualTo(bb.getShort(1));
        assertThat(ByteArrayReader.getInt(WIRE, 3)).isEqualTo(bb.getInt(3));
        assertThat(ByteArrayReader.getLong(WIRE, 7)).isEqualTo(bb.getLong(7));
    }

    @Test
    void readAtNonZeroOffset_correctValue() {
        // Simulate reading a field embedded in the middle of a larger message.
        byte[] msg = new byte[32];
        // Write a known long at offset 12
        long expected = 0xCAFEBABEDEAD0000L;
        ByteBuffer.wrap(msg).order(ByteOrder.BIG_ENDIAN).putLong(12, expected);
        assertThat(ByteArrayReader.getLong(msg, 12)).isEqualTo(expected);
    }
}

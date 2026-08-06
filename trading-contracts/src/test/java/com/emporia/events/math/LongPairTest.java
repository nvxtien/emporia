package com.emporia.events.math;

import com.emporia.events.sbe.SbeEncoderDecoder;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LongPairTest {

    @Test
    void roundTripFromAndToUuidPreservesBits() {
        UUID expected = UUID.randomUUID();
        LongPair pair = LongPair.fromUuid(expected);

        assertThat(pair.mostSigBits()).isEqualTo(expected.getMostSignificantBits());
        assertThat(pair.leastSigBits()).isEqualTo(expected.getLeastSignificantBits());
        assertThat(pair.toUuid()).isEqualTo(expected);
        assertThat(pair.isZero()).isFalse();
    }

    @Test
    void nullAndZeroUuidBehavesAsZero() {
        LongPair nullPair = LongPair.fromUuid(null);
        LongPair zeroPair = LongPair.of(0L, 0L);

        assertThat(nullPair).isEqualTo(LongPair.ZERO);
        assertThat(nullPair.isZero()).isTrue();
        assertThat(nullPair.toUuid()).isNull();
        assertThat(zeroPair.isZero()).isTrue();
    }

    @Test
    void sbeEncoderDecoderBufferRoundTripWithLongPair() {
        LongPair original = LongPair.of(0x123456789ABCDEF0L, 0x0FEDCBA987654321L);
        ByteBuffer buf = ByteBuffer.allocate(16);

        SbeEncoderDecoder.putUuid(buf, original);
        buf.flip();

        LongPair decoded = SbeEncoderDecoder.getLongPair(buf);
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void sbeEncoderDecoderBufferRoundTripWithPrimitiveLongs() {
        long most = 0x5555555555555555L;
        long least = 0xAAAAAAAAAAAAAAAAL;
        ByteBuffer buf = ByteBuffer.allocate(16);

        SbeEncoderDecoder.putUuid(buf, most, least);
        buf.flip();

        LongPair decoded = SbeEncoderDecoder.getLongPair(buf);
        assertThat(decoded.mostSigBits()).isEqualTo(most);
        assertThat(decoded.leastSigBits()).isEqualTo(least);
    }

    @Test
    void naturalOrderingComparesMostThenLeast() {
        LongPair small = LongPair.of(1L, 100L);
        LongPair medium = LongPair.of(1L, 200L);
        LongPair large = LongPair.of(2L, 50L);

        assertThat(small.compareTo(medium)).isNegative();
        assertThat(medium.compareTo(large)).isNegative();
        assertThat(large.compareTo(small)).isPositive();
    }
}

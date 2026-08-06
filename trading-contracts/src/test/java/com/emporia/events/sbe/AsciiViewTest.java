package com.emporia.events.sbe;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsciiViewTest {

    // ── Construction ──────────────────────────────────────────────────────────

    @Test
    void emptyViewIsPresent_false() {
        assertThat(AsciiView.EMPTY.isPresent()).isFalse();
        assertThat(AsciiView.EMPTY.length()).isZero();
        assertThat(AsciiView.EMPTY.toString()).isEmpty();
    }

    @Test
    void nullBufferThrows() {
        assertThatThrownBy(() -> new AsciiView(null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidRegionThrows() {
        byte[] buf = "HELLO".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new AsciiView(buf, 3, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid region");
    }

    // ── equalsAscii ───────────────────────────────────────────────────────────

    @Test
    void equalsAscii_exact() {
        byte[] buf = "CREATED".getBytes(StandardCharsets.UTF_8);
        AsciiView view = new AsciiView(buf, 0, buf.length);
        assertThat(view.equalsAscii("CREATED")).isTrue();
        assertThat(view.equalsAscii("FILLED")).isFalse();
        assertThat(view.equalsAscii("CREATE")).isFalse(); // shorter
        assertThat(view.equalsAscii("CREATED_X")).isFalse(); // longer
    }

    @Test
    void equalsAscii_onSliceOfLargerBuffer() {
        // Simulates the SBE decode: view points into the middle of the message buffer
        byte[] buf = "...XNAS---".getBytes(StandardCharsets.UTF_8);
        AsciiView view = new AsciiView(buf, 3, 4); // "XNAS"
        assertThat(view.equalsAscii("XNAS")).isTrue();
        assertThat(view.equalsAscii("XNYS")).isFalse();
    }

    @Test
    void equalsAscii_emptyStringAndNullNull() {
        assertThat(AsciiView.EMPTY.equalsAscii("")).isTrue();
        assertThat(AsciiView.EMPTY.equalsAscii(null)).isTrue();
    }

    // ── equalsAsciiIgnoreCase ─────────────────────────────────────────────────

    @Test
    void equalsAsciiIgnoreCase_destinations() {
        byte[] buf = "smart".getBytes(StandardCharsets.UTF_8);
        AsciiView view = new AsciiView(buf, 0, buf.length);
        assertThat(view.equalsAsciiIgnoreCase("SMART")).isTrue();
        assertThat(view.equalsAsciiIgnoreCase("Smart")).isTrue();
        assertThat(view.equalsAsciiIgnoreCase("smart")).isTrue();
        assertThat(view.equalsAsciiIgnoreCase("DMA")).isFalse();
    }

    // ── CharSequence ──────────────────────────────────────────────────────────

    @Test
    void charAtAndLength() {
        byte[] buf = "DMA".getBytes(StandardCharsets.UTF_8);
        AsciiView view = new AsciiView(buf, 0, 3);
        assertThat(view.length()).isEqualTo(3);
        assertThat(view.charAt(0)).isEqualTo('D');
        assertThat(view.charAt(1)).isEqualTo('M');
        assertThat(view.charAt(2)).isEqualTo('A');
    }

    @Test
    void charAtOutOfBoundsThrows() {
        AsciiView view = new AsciiView("AB".getBytes(StandardCharsets.UTF_8), 0, 2);
        assertThatThrownBy(() -> view.charAt(2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> view.charAt(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void subSequence_returnsCorrectSlice() {
        byte[] buf = "CANCEL_REQUESTED".getBytes(StandardCharsets.UTF_8);
        AsciiView view = new AsciiView(buf, 0, buf.length);
        CharSequence sub = view.subSequence(0, 6);
        assertThat(sub.toString()).isEqualTo("CANCEL");
    }

    @Test
    void subSequence_emptyRange_returnsEmpty() {
        byte[] buf = "XNAS".getBytes(StandardCharsets.UTF_8);
        AsciiView view = new AsciiView(buf, 0, buf.length);
        assertThat(view.subSequence(2, 2)).isSameAs(AsciiView.EMPTY);
    }

    // ── toString — lazy, cached ───────────────────────────────────────────────

    @Test
    void toString_materialisesOnce() {
        byte[] buf = "DESK-ALPHA".getBytes(StandardCharsets.UTF_8);
        AsciiView view = new AsciiView(buf, 0, buf.length);
        String first  = view.toString();
        String second = view.toString();
        assertThat(first).isEqualTo("DESK-ALPHA");
        assertThat(first).isSameAs(second); // same object — cached
    }

    @Test
    void toString_onSlice_correctSubstring() {
        byte[] buf = "PREFIX:DESK-BETA:SUFFIX".getBytes(StandardCharsets.UTF_8);
        AsciiView view = new AsciiView(buf, 7, 9); // "DESK-BETA"
        assertThat(view.toString()).isEqualTo("DESK-BETA");
    }

    // ── equals / hashCode ─────────────────────────────────────────────────────

    @Test
    void equalsAndHashCode_twoViewsOfSameContent() {
        byte[] a = "XNAS".getBytes(StandardCharsets.UTF_8);
        byte[] b = ("__XNAS__").getBytes(StandardCharsets.UTF_8);
        AsciiView va = new AsciiView(a, 0, 4);
        AsciiView vb = new AsciiView(b, 2, 4);
        assertThat(va).isEqualTo(vb);
        assertThat(va.hashCode()).isEqualTo(vb.hashCode());
    }

    @Test
    void equalsAndHashCode_consistentWithString() {
        String text = "CANCEL_REQUESTED";
        byte[] buf = text.getBytes(StandardCharsets.UTF_8);
        AsciiView view = new AsciiView(buf, 0, buf.length);
        // AsciiView.hashCode must equal String.hashCode for ASCII content
        assertThat(view.hashCode()).isEqualTo(text.hashCode());
        // String implements CharSequence, so equals delegates to toString comparison
        assertThat(view.equals(text)).isTrue();
        assertThat(view.toString()).isEqualTo(text);
    }

    @Test
    void notEqual_differentLengths() {
        AsciiView a = new AsciiView("DMA".getBytes(StandardCharsets.UTF_8), 0, 3);
        AsciiView b = new AsciiView("DMA ".getBytes(StandardCharsets.UTF_8), 0, 4);
        assertThat(a).isNotEqualTo(b);
    }
}

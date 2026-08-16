package com.emporia.execution.quoting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManualQuoteUniverseTest {

    @Test
    void quotesTheConfiguredListings() {
        assertThat(new ManualQuoteUniverse("1,2,3", "").listings()).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    /**
     * The exclusion list layers over the strategy rather than replacing it,
     * because the reasons to stop quoting one instrument - a suspension, news
     * pending, a position that just lost money - arrive faster than any
     * re-evaluation cycle.
     */
    @Test
    void theExclusionListWinsOverTheIncludeList() {
        assertThat(new ManualQuoteUniverse("1,2,3", "2").listings()).containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void excludingSomethingNeverQuotedIsHarmless() {
        assertThat(new ManualQuoteUniverse("1", "99").listings()).containsExactly(1L);
    }

    @Test
    void excludingEverythingLeavesNothingQuoted() {
        assertThat(new ManualQuoteUniverse("1,2", "1,2").listings()).isEmpty();
    }

    /**
     * Quoting nothing is the correct state before anyone has decided what to
     * quote, and the right default for a deployment that only routes. It must
     * not be a startup failure.
     */
    @Test
    void anEmptyUniverseIsAValidState() {
        assertThat(new ManualQuoteUniverse("", "").listings()).isEmpty();
        assertThat(new ManualQuoteUniverse("  ", "  ").listings()).isEmpty();
    }

    @Test
    void toleratesSpacingAndTrailingSeparators() {
        assertThat(new ManualQuoteUniverse(" 1 , 2 ,", " ,3, ").listings()).containsExactlyInAnyOrder(1L, 2L);
    }

    /**
     * A typo must not silently shrink the universe. Emporia would stop quoting
     * an instrument it was told to quote, and nothing would report it - the
     * failure mode this codebase keeps converting into a startup error.
     */
    @Test
    void refusesToStartOnAMalformedListingId() {
        assertThatThrownBy(() -> new ManualQuoteUniverse("1,two,3", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("emporia.quoting.listings")
                .hasMessageContaining("two");

        assertThatThrownBy(() -> new ManualQuoteUniverse("1", "2,x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("emporia.quoting.excluded-listings");
    }

    @Test
    void reportsTheStrategyItServes() {
        assertThat(new ManualQuoteUniverse("1", "").strategy()).isEqualTo("manual");
    }

    @Test
    void theUniverseIsImmutableOnceBuilt() {
        ManualQuoteUniverse universe = new ManualQuoteUniverse("1,2", "");
        assertThatThrownBy(() -> universe.listings().add(3L))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

package com.emporia.execution.quoting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The configured list, minus anything on the exclusion list.
 *
 * <p>Default strategy, and the only one implemented. See {@link QuoteUniverse}
 * for why the others are risk postures rather than refinements.
 *
 * <h2>The exclusion list is not a smaller version of the include list</h2>
 * <p>It layers over whichever strategy is active, including the generated ones
 * that do not exist yet, and it exists because the reasons to stop quoting an
 * instrument arrive faster than any re-evaluation cycle: a suspension, news
 * pending, a position that just lost money. Those need to take effect now, not
 * at the next session boundary.
 *
 * <p>Same reasoning as the deduplication index's startup guard: what is
 * dangerous has to be stoppable faster than the automatic path that would
 * eventually notice.
 */
@Component
@ConditionalOnProperty(name = "emporia.quoting.universe", havingValue = "manual", matchIfMissing = true)
public class ManualQuoteUniverse implements QuoteUniverse {

    private static final Logger log = LoggerFactory.getLogger(ManualQuoteUniverse.class);

    private final Set<Long> listings;

    public ManualQuoteUniverse(@Value("${emporia.quoting.listings:}") String listings,
                               @Value("${emporia.quoting.excluded-listings:}") String excluded) {
        Set<Long> included = parse(listings, "emporia.quoting.listings");
        Set<Long> exclusions = parse(excluded, "emporia.quoting.excluded-listings");
        included.removeAll(exclusions);
        this.listings = Set.copyOf(included);
        if (this.listings.isEmpty()) {
            // Not a failure: quoting nothing is the correct state before anyone
            // has decided what to quote, and it is the safe default for a
            // deployment that only routes.
            log.info("Quote universe is empty; Emporia will quote nothing until emporia.quoting.listings is set");
        } else {
            log.info("Quote universe: {} listing(s) {}{}", this.listings.size(), this.listings,
                    exclusions.isEmpty() ? "" : ", excluding " + exclusions);
        }
    }

    @Override
    public Set<Long> listings() {
        return listings;
    }

    @Override
    public String strategy() {
        return "manual";
    }

    private static Set<Long> parse(String raw, String property) {
        if (raw == null || raw.isBlank()) return new LinkedHashSet<>();
        Set<Long> parsed = new LinkedHashSet<>();
        for (String entry : Arrays.stream(raw.split(",")).map(String::strip).filter(e -> !e.isEmpty()).toList()) {
            try {
                parsed.add(Long.parseLong(entry));
            } catch (NumberFormatException malformed) {
                // Loudly, and refusing to start. A typo that silently drops a
                // listing from the universe means Emporia quietly stops quoting
                // an instrument it was told to quote, and nothing reports it.
                throw new IllegalStateException(
                        property + " contains '" + entry + "', which is not a listing id", malformed);
            }
        }
        return parsed;
    }
}

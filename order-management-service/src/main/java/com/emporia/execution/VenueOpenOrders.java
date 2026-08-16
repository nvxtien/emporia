package com.emporia.execution;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What a venue says it is currently holding open, answered in
 * order-management's own order ids.
 *
 * <p>The translation into order-management ids belongs to the gateway because
 * the identifier scheme is the venue's: {@code exchange-core} derives a
 * {@code long} from the order UUID, a FIX venue answers with its own
 * {@code OrderID}. A caller that had to know which is which would have to know
 * which venue it is talking to, and that is exactly the coupling that kept
 * order reconciliation out of the agency artifact.
 *
 * <h2>Why {@code supported} is a field and not an empty answer</h2>
 * <p>A gateway that cannot answer must not answer "nothing". An empty
 * {@link #known()} from a venue that was never asked reads as *every live order
 * is missing* - the loudest possible false positive, on the one report whose
 * whole job is to be believed. So not-implemented is a distinct state, and the
 * caller has to handle it rather than being handed a plausible-looking lie.
 *
 * @param supported       whether the venue can answer this question at all
 * @param known           order-management ids the venue confirms it holds open
 * @param unknownToCaller venue-side descriptions of orders the venue holds that
 *                        were not asked about - "ghosts", in venue terms
 *                        because order-management has no id for them
 */
record VenueOpenOrders(boolean supported, Set<UUID> known, List<String> unknownToCaller) {

    VenueOpenOrders {
        known = Set.copyOf(known);
        unknownToCaller = List.copyOf(unknownToCaller);
    }

    static VenueOpenOrders unsupported() {
        return new VenueOpenOrders(false, Set.of(), List.of());
    }

    static VenueOpenOrders of(Set<UUID> known, List<String> unknownToCaller) {
        return new VenueOpenOrders(true, known, unknownToCaller);
    }
}

package com.emporia.execution.quoting;

import java.util.Set;

/**
 * Which listings Emporia is willing to quote a two-sided price on.
 *
 * <p>The universe is where market-making risk is chosen, so the strategy is not
 * an implementation detail. Three are foreseen and they are three risk postures,
 * not three ways of writing the same thing:
 *
 * <ul>
 *   <li><b>liquidity</b> — quote where the reference market is deep and tight.
 *       Hedging is cheap and informed flow is diluted among many participants.
 *       This is what market makers do, and it needs a statistics accumulator
 *       that does not exist yet: market-data carries volume and depth but no
 *       average daily volume.</li>
 *   <li><b>client-portfolio</b> — quote what clients actually trade, which
 *       maximises how much flow is captured. Safe for retail, which is
 *       uninformed - that is why wholesalers pay for it - and dangerous for
 *       institutional, which is not. The same setting is a good decision in
 *       quadrant (1) and a poor one in quadrant (3).</li>
 *   <li><b>manual</b> — a configured list. The only one implemented, and the one
 *       to start from regardless: nobody runs market making for the first time
 *       off a generated list, and it is what limits initial risk and makes tests
 *       deterministic.</li>
 * </ul>
 *
 * <h2>This is a slow decision</h2>
 * <p>Re-evaluated per session, not per order. Quote <i>prices</i> move in
 * milliseconds; which instruments are quoted at all moves in days. Anything
 * consulting this on the hot path has misunderstood it.
 */
public interface QuoteUniverse {

    /** Listing ids eligible to be quoted, after exclusions. */
    Set<Long> listings();

    /** Which {@code emporia.quoting.universe} value this serves. */
    String strategy();
}

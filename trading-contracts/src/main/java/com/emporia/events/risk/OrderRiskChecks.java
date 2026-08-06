package com.emporia.events.risk;

import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.math.FixedPointMath;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

/**
 * Pure order-risk evaluation.
 *
 * <p>Hard validation is kept deterministic and side-effect free so callers can
 * emit their own telemetry separately.
 *
 * <p>Two overloads are provided:
 * <ul>
 *   <li>{@link #evaluate(OrderType, BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal)} —
 *       boundary-facing path (HTTP controllers, Kafka consumers receiving external input).
 *       Accepts {@link BigDecimal} and delegates to the long path after scaling.</li>
 *   <li>{@link #evaluate(OrderType, long, long, long, long, long)} —
 *       hot-path, GC-free version operating entirely on fixed-point {@code long} (scale 6).
 *       Zero heap allocation when the caller already holds scaled values.</li>
 * </ul>
 */
public final class OrderRiskChecks {
    private static final Set<OrderType> MARKET_TYPES = EnumSet.of(OrderType.MARKET);

    private OrderRiskChecks() {
    }

    // ── BigDecimal boundary overload ─────────────────────────────────────────

    public static RiskOutcome evaluate(OrderType type, BigDecimal quantity, BigDecimal increment,
                                       BigDecimal traded, BigDecimal price, BigDecimal tickSize) {
        long qtyScaled       = FixedPointMath.toScaledLong(quantity);
        long incrementScaled = FixedPointMath.toScaledLong(increment);
        long tradedScaled    = FixedPointMath.toScaledLong(traded);
        long priceScaled     = price == null ? 0L : FixedPointMath.toScaledLong(price);
        long tickScaled      = FixedPointMath.toScaledLong(tickSize);
        RiskOutcome outcome = evaluate(type, qtyScaled, incrementScaled, tradedScaled, priceScaled, tickScaled);
        // Re-wrap the validated price as BigDecimal so the existing callers get the same contract.
        if (outcome.allowed() && outcome.validatedPriceScaled() != 0L) {
            return new RiskOutcome(true, 200, "ok", "ok",
                    FixedPointMath.toBigDecimal(outcome.validatedPriceScaled()),
                    outcome.validatedPriceScaled());
        }
        return outcome;
    }

    // ── GC-free long overload (hot path) ────────────────────────────────────

    /**
     * Zero-allocation risk evaluation operating on fixed-point {@code long} values
     * (scale 6, i.e. multiply decimal value by {@link FixedPointMath#SCALE_FACTOR}).
     *
     * <p>All arithmetic is primitive. No heap object is allocated by this method.
     *
     * @param qtyScaled       order quantity × 1_000_000
     * @param incrementScaled lot/size increment × 1_000_000
     * @param tradedScaled    already-traded quantity × 1_000_000
     * @param priceScaled     limit price × 1_000_000, or {@code 0} for MARKET orders
     * @param tickScaled      tick size × 1_000_000
     */
    public static RiskOutcome evaluate(OrderType type,
                                       long qtyScaled, long incrementScaled, long tradedScaled,
                                       long priceScaled, long tickScaled) {
        if (qtyScaled <= 0L) {
            return deny("quantity", "Quantity must be greater than zero");
        }
        if (qtyScaled <= tradedScaled) {
            return deny("quantity", "Quantity must be greater than the quantity already traded");
        }
        if (incrementScaled <= 0L) {
            return deny("quantity", "Listing size increment must be greater than zero");
        }
        if (qtyScaled % incrementScaled != 0L) {
            return deny("quantity", "Quantity must align with the listing size increment");
        }
        if (tickScaled <= 0L) {
            return deny("symbol", "Listing tick size must be greater than zero");
        }
        if (isMarket(type)) {
            return priceScaled == 0L
                    ? allowMarket()
                    : deny("symbol", "Market orders cannot have a limit price");
        }
        if (priceScaled <= 0L) {
            return deny("symbol", "A positive limit price is required for limit orders");
        }
        if (priceScaled % tickScaled != 0L) {
            return deny("symbol", "Limit price must align with the listing tick size");
        }
        return allowLimit(priceScaled);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean isMarket(OrderType type) {
        return type != null && MARKET_TYPES.contains(type);
    }

    private static RiskOutcome allowMarket() {
        return new RiskOutcome(true, 200, "ok", "ok", null, 0L);
    }

    private static RiskOutcome allowLimit(long priceScaled) {
        return new RiskOutcome(true, 200, "ok", "ok",
                FixedPointMath.toBigDecimal(priceScaled), priceScaled);
    }

    private static RiskOutcome deny(String reason, String message) {
        return new RiskOutcome(false, 400, reason, message, null, 0L);
    }

    // ── Result record ────────────────────────────────────────────────────────

    /**
     * Result of a risk evaluation.
     *
     * <p>{@code validatedPrice} is the {@link BigDecimal} form, populated lazily for
     * callers that need it (controllers, Kafka publishers). {@code validatedPriceScaled}
     * is the raw fixed-point long, available at zero cost on all outcomes.
     */
    public record RiskOutcome(boolean allowed, int status, String reason, String message,
                               BigDecimal validatedPrice, long validatedPriceScaled) {

        /** Legacy single-field constructor — keeps existing callers compiling unchanged. */
        public RiskOutcome(boolean allowed, int status, String reason, String message,
                            BigDecimal validatedPrice) {
            this(allowed, status, reason, message, validatedPrice,
                    validatedPrice == null ? 0L : FixedPointMath.toScaledLong(validatedPrice));
        }
    }
}
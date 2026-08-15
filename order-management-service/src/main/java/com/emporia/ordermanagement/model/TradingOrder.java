package com.emporia.ordermanagement.model;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import com.emporia.events.time.DomainClock;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trading_order")
@Getter
public class TradingOrder {
    @Id
    private UUID id;
    /**
     * The revision callers echo back as {@code expectedVersion}, advanced by
     * {@link #recordRevision()} and by nothing else.
     *
     * <p>Deliberately <b>not</b> {@code @Version}. Hibernate's optimistic
     * locking is not wanted here: order state changes all run on the single
     * Disruptor writer thread, so the race it guards against - a venue fill and
     * a user cancel updating one row at once - cannot occur, and the writes that
     * would carry the guard go out through raw JDBC where Hibernate is not
     * involved at all.
     *
     * <p>Keeping the annotation was not free. It made Spring Data decide new
     * from existing by {@code version == null}, and this constructor assigns 0,
     * so every {@code save()} of a brand-new order took the {@code merge()} path
     * and failed against a real database with an optimistic-lock error for a
     * conflict that never happened. It also meant the version incremented twice
     * on the repository path - once here, once at flush - while incrementing
     * once on the hot path.
     */
    @Column(name = "entity_version", nullable = false)
    private Long version;
    @Column(name = "user_subject", nullable = false, length = 200)
    private String userSubject;
    @Column(name = "desk_id", nullable = false, length = 100)
    private String deskId;
    @Embedded
    private ListingDetails listing;
    @Enumerated(EnumType.STRING) @Column(name = "order_side", nullable = false, length = 8)
    private OrderSide side;
    @Enumerated(EnumType.STRING) @Column(name = "order_type", nullable = false, length = 16)
    private OrderType type;
    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity;
    @Column(name = "limit_price", precision = 19, scale = 6)
    private BigDecimal limitPrice;
    @Column(name = "remaining_quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal remainingQuantity;
    @Column(name = "traded_quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal tradedQuantity;
    @Column(name = "average_trade_price", precision = 19, scale = 6)
    private BigDecimal averageTradePrice;
    @Enumerated(EnumType.STRING) @Column(name = "order_status", nullable = false, length = 24)
    private OrderStatus status;
    @Enumerated(EnumType.STRING) @Column(name = "target_status", nullable = false, length = 24)
    private OrderStatus targetStatus;
    @Column(nullable = false, length = 32)
    private String destination;
    @Column(name = "originator_reference", nullable = false, length = 100)
    private String originatorReference;
    @Column(name = "parent_order_id")
    private UUID parentOrderId;
    @Column(name = "root_order_id", nullable = false)
    private UUID rootOrderId;
    @Column(name = "execution_parameters")
    private String executionParameters;
    @Column(name = "error_message", length = 500)
    private String errorMessage;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TradingOrder() { }

    public TradingOrder(UUID id, String userSubject, ListingSnapshot listing, OrderSide side, OrderType type,
                 BigDecimal quantity, BigDecimal limitPrice, String destination, String originatorReference,
                 UUID parentOrderId, UUID rootOrderId, String executionParameters) {
        this(id, userSubject, userSubject, listing, side, type, quantity, limitPrice, destination,
                originatorReference, parentOrderId, rootOrderId, executionParameters);
    }

    public TradingOrder(UUID id, String userSubject, String deskId, ListingSnapshot listing, OrderSide side,
                 OrderType type, BigDecimal quantity, BigDecimal limitPrice, String destination,
                 String originatorReference, UUID parentOrderId, UUID rootOrderId, String executionParameters) {
        this.id = id; this.version = 0L; this.userSubject = userSubject; this.deskId = deskId; this.listing = new ListingDetails(listing); this.side = side;
        this.type = type; this.quantity = quantity; this.limitPrice = limitPrice; this.remainingQuantity = quantity;
        this.tradedQuantity = BigDecimal.ZERO; this.status = OrderStatus.LIVE; this.targetStatus = OrderStatus.LIVE;
        this.destination = destination; this.originatorReference = originatorReference; this.parentOrderId = parentOrderId;
        this.rootOrderId = rootOrderId == null ? id : rootOrderId; this.executionParameters = executionParameters;
        this.createdAt = DomainClock.now(); this.updatedAt = this.createdAt;
        validateInvariants();
    }

    public TradingOrder(UUID id, String userSubject, String deskId, ListingSnapshot listing, OrderSide side,
                 OrderType type, long quantityScaled, long limitPriceScaled, String destination,
                 String originatorReference, UUID parentOrderId, UUID rootOrderId, String executionParameters) {
        this(id, userSubject, deskId, listing, side, type,
                com.emporia.events.math.FixedPointMath.toBigDecimal(quantityScaled),
                type == OrderType.MARKET || limitPriceScaled == 0L ? null : com.emporia.events.math.FixedPointMath.toBigDecimal(limitPriceScaled),
                destination, originatorReference, parentOrderId, rootOrderId, executionParameters);
    }

    public synchronized void modify(long newQuantityScaled, long newLimitPriceScaled) {
        require(status == OrderStatus.LIVE || status == OrderStatus.PARTIALLY_FILLED,
                "Only active orders can be modified");
        require(targetStatus != OrderStatus.CANCELLED,
                "Orders pending cancellation cannot be modified");
        validateQuantityScaled(newQuantityScaled);
        long currentTradedQtyScaled = com.emporia.events.math.FixedPointMath.toScaledLong(tradedQuantity);
        require(newQuantityScaled > currentTradedQtyScaled,
                "Modified quantity must be greater than the quantity already traded");
        validatePriceScaled(type == OrderType.MARKET ? null : newLimitPriceScaled);

        this.quantity = com.emporia.events.math.FixedPointMath.toBigDecimal(newQuantityScaled);
        this.limitPrice = type == OrderType.MARKET || newLimitPriceScaled == 0L ? null : com.emporia.events.math.FixedPointMath.toBigDecimal(newLimitPriceScaled);
        this.remainingQuantity = com.emporia.events.math.FixedPointMath.toBigDecimal(newQuantityScaled - currentTradedQtyScaled);
        updatedAt = DomainClock.now();
        validateInvariants();
    }

    public synchronized void modify(BigDecimal newQuantity, BigDecimal newLimitPrice) {
        long newQtyScaled = com.emporia.events.math.FixedPointMath.toScaledLong(newQuantity);
        long newPriceScaled = newLimitPrice == null ? 0L : com.emporia.events.math.FixedPointMath.toScaledLong(newLimitPrice);
        modify(newQtyScaled, newPriceScaled);
    }

    public synchronized void applyFill(long fillQuantityScaled, long fillPriceScaled) {
        require(status == OrderStatus.LIVE || status == OrderStatus.PARTIALLY_FILLED
                        || status == OrderStatus.CANCELLED,
                "Only active or cancelled orders can receive fills");
        require(fillQuantityScaled > 0L, "Fill quantity must be greater than zero");
        long incrementScaled = listing == null ? 0L : listing.getSizeIncrementScaled();
        require(incrementScaled > 0L && fillQuantityScaled % incrementScaled == 0L,
                "Fill quantity must align with the listing size increment");
        long remQtyScaled = com.emporia.events.math.FixedPointMath.toScaledLong(remainingQuantity);
        require(fillQuantityScaled <= remQtyScaled, "Fill quantity cannot exceed remaining quantity");
        require(fillPriceScaled > 0L, "Fill price must be greater than zero");

        long currentTradedQtyScaled = com.emporia.events.math.FixedPointMath.toScaledLong(tradedQuantity);
        long currentAvgPriceScaled = com.emporia.events.math.FixedPointMath.toScaledLong(averageTradePrice);

        long newAvgPriceScaled = com.emporia.events.math.FixedPointMath.calculateWeightedAveragePrice(
                currentTradedQtyScaled, currentAvgPriceScaled, fillQuantityScaled, fillPriceScaled
        );

        long newTradedQtyScaled = currentTradedQtyScaled + fillQuantityScaled;
        long totalQtyScaled = com.emporia.events.math.FixedPointMath.toScaledLong(quantity);
        long newRemQtyScaled = totalQtyScaled - newTradedQtyScaled;

        this.tradedQuantity = com.emporia.events.math.FixedPointMath.toBigDecimal(newTradedQtyScaled);
        this.remainingQuantity = com.emporia.events.math.FixedPointMath.toBigDecimal(newRemQtyScaled);
        this.averageTradePrice = com.emporia.events.math.FixedPointMath.toBigDecimal(newAvgPriceScaled);

        if (newRemQtyScaled == 0L) {
            status = OrderStatus.FILLED;
            targetStatus = OrderStatus.FILLED;
        } else if (status != OrderStatus.CANCELLED) {
            status = OrderStatus.PARTIALLY_FILLED;
            if (targetStatus != OrderStatus.CANCELLED) targetStatus = OrderStatus.LIVE;
        }
        updatedAt = DomainClock.now();
        validateInvariants();
    }

    public synchronized void applyFill(BigDecimal fillQuantity, BigDecimal fillPrice) {
        require(fillQuantity != null && fillQuantity.signum() > 0, "Fill quantity must be greater than zero");
        require(fillPrice != null && fillPrice.signum() > 0, "Fill price must be greater than zero");
        applyFill(com.emporia.events.math.FixedPointMath.toScaledLong(fillQuantity),
                  com.emporia.events.math.FixedPointMath.toScaledLong(fillPrice));
    }

    public synchronized void requestCancel() {
        require(status == OrderStatus.LIVE || status == OrderStatus.PARTIALLY_FILLED,
                "Only active orders can be cancelled");
        require(targetStatus != OrderStatus.CANCELLED,
                "Order cancellation is already pending");
        targetStatus = OrderStatus.CANCELLED;
        updatedAt = DomainClock.now();
        validateInvariants();
    }

    public synchronized void confirmCancel() {
        require(status == OrderStatus.LIVE || status == OrderStatus.PARTIALLY_FILLED,
                "Only active orders can confirm cancellation");
        targetStatus = OrderStatus.CANCELLED;
        status = OrderStatus.CANCELLED;
        updatedAt = DomainClock.now();
        validateInvariants();
    }

    /**
     * Convenience transition retained for focused domain tests. Application
     * command handling uses requestCancel/confirmCancel so venue executions can
     * win the race before a cancellation acknowledgement.
     */
    public synchronized void cancel() {
        requestCancel();
        confirmCancel();
    }

    /**
     * Advances the revision this order publishes to callers.
     *
     * <p>Called by {@link com.emporia.ordermanagement.service.OrderStateCache}
     * once per committed state change, and nowhere else - it is now the only
     * thing that moves this column.
     *
     * <h2>Why it is needed at all</h2>
     * <p>Order writes moved to raw JDBC in {@code AsyncDbWriter}, which bypasses
     * Hibernate, so nothing incremented the column any more. Every order sat at
     * version 0 for its whole life, which made the {@code expectedVersion} check
     * in {@code OrderCommandHandler.modify} compare 0 against 0 and pass every
     * time - an optimistic-lock guard the API advertises and that had silently
     * stopped firing.
     */
    public synchronized void recordRevision() {
        this.version = (this.version == null ? 0L : this.version) + 1L;
    }

    public synchronized void reject(String message) {
        require(status == OrderStatus.LIVE && tradedQuantity.signum() == 0,
                "Only unfilled live orders can be rejected");
        status = OrderStatus.REJECTED;
        targetStatus = OrderStatus.REJECTED;
        errorMessage = message == null || message.isBlank() ? "Execution venue rejected the order" : message;
        updatedAt = DomainClock.now();
        validateInvariants();
    }

    @PrePersist
    @PreUpdate
    public synchronized void validateInvariants() {
        require(userSubject != null && !userSubject.isBlank(), "Order owner is required");
        require(deskId != null && !deskId.isBlank(), "Order desk is required");
        require(listing != null, "Listing details are required");
        require(side != null, "Order side is required");
        require(type != null, "Order type is required");
        require(status != null, "Order status is required");
        require(targetStatus != null, "Target status is required");
        validateQuantity(quantity);
        require(tradedQuantity != null && tradedQuantity.signum() >= 0,
                "Traded quantity cannot be negative");
        require(remainingQuantity != null && remainingQuantity.signum() >= 0,
                "Remaining quantity cannot be negative");
        require(tradedQuantity.add(remainingQuantity).compareTo(quantity) == 0,
                "Traded plus remaining quantity must equal total quantity");
        require(tradedQuantity.compareTo(quantity) <= 0,
                "Traded quantity cannot exceed total quantity");
        validatePrice(limitPrice);
        require(averageTradePrice == null || averageTradePrice.signum() > 0,
                "Average trade price must be positive when present");

        switch (status) {
            case LIVE, REJECTED -> {
                require(tradedQuantity.signum() == 0,
                        status + " orders cannot have traded quantity");
                require(remainingQuantity.compareTo(quantity) == 0,
                        status + " orders must retain their full quantity");
            }
            case PARTIALLY_FILLED -> {
                require(tradedQuantity.signum() > 0,
                        "Partially filled orders must have traded quantity");
                require(remainingQuantity.signum() > 0,
                        "Partially filled orders must have remaining quantity");
            }
            case FILLED -> {
                require(tradedQuantity.compareTo(quantity) == 0,
                        "Filled orders must have traded their full quantity");
                require(remainingQuantity.signum() == 0,
                        "Filled orders cannot have remaining quantity");
            }
            case CANCELLED -> {
                require(tradedQuantity.compareTo(quantity) < 0,
                        "Cancelled orders cannot already be fully filled");
                require(remainingQuantity.signum() > 0,
                        "Cancelled orders must have unfilled quantity");
            }
        }

        if (targetStatus == OrderStatus.CANCELLED) {
            require(status == OrderStatus.LIVE || status == OrderStatus.PARTIALLY_FILLED
                            || status == OrderStatus.CANCELLED,
                    "Only active or cancelled orders can target cancellation");
        } else {
            require(targetStatus == status
                            || targetStatus == OrderStatus.LIVE
                            && (status == OrderStatus.LIVE || status == OrderStatus.PARTIALLY_FILLED),
                    "Orders without a pending cancellation must target their current lifecycle");
        }
    }

    private void validateQuantity(BigDecimal candidate) {
        validateQuantityScaled(com.emporia.events.math.FixedPointMath.toScaledLong(candidate));
    }

    private void validateQuantityScaled(long candidateScaled) {
        require(candidateScaled > 0L, "Quantity must be greater than zero");
        long incrementScaled = listing == null ? 0L : listing.getSizeIncrementScaled();
        require(incrementScaled > 0L, "Listing size increment must be greater than zero");
        require(candidateScaled % incrementScaled == 0L, "Quantity must align with the listing size increment");
    }

    private void validatePrice(BigDecimal candidate) {
        validatePriceScaled(candidate == null ? null : com.emporia.events.math.FixedPointMath.toScaledLong(candidate));
    }

    private void validatePriceScaled(Long candidateScaled) {
        long tickSizeScaled = listing == null ? 0L : listing.getTickSizeScaled();
        require(tickSizeScaled > 0L, "Listing tick size must be greater than zero");
        if (type == OrderType.MARKET) {
            require(candidateScaled == null || candidateScaled == 0L, "Market orders cannot have a limit price");
            return;
        }
        require(candidateScaled != null && candidateScaled > 0L, "Limit orders require a positive limit price");
        require(candidateScaled % tickSizeScaled == 0L, "Limit price must align with the listing tick size");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public synchronized OrderView view() {
        return new OrderView(id, version, userSubject, deskId, listing.snapshot(), side, type, quantity, limitPrice, remainingQuantity,
                tradedQuantity, averageTradePrice, status, targetStatus, destination, originatorReference,
                parentOrderId, rootOrderId, executionParameters, errorMessage, createdAt, updatedAt);
    }
}

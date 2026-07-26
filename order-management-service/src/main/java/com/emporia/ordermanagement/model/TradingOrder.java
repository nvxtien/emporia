package com.emporia.ordermanagement.model;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trading_order")
@Getter
public class TradingOrder {
    @Id private UUID id;
    @Version @Column(name = "entity_version", nullable = false) private Long version;
    @Column(name = "user_subject", nullable = false, length = 200) private String userSubject;
    @Column(name = "desk_id", nullable = false, length = 100) private String deskId;
    @Embedded private ListingDetails listing;
    @Enumerated(EnumType.STRING) @Column(name = "order_side", nullable = false, length = 8) private OrderSide side;
    @Enumerated(EnumType.STRING) @Column(name = "order_type", nullable = false, length = 16) private OrderType type;
    @Column(nullable = false, precision = 19, scale = 6) private BigDecimal quantity;
    @Column(name = "limit_price", precision = 19, scale = 6) private BigDecimal limitPrice;
    @Column(name = "remaining_quantity", nullable = false, precision = 19, scale = 6) private BigDecimal remainingQuantity;
    @Column(name = "traded_quantity", nullable = false, precision = 19, scale = 6) private BigDecimal tradedQuantity;
    @Column(name = "average_trade_price", precision = 19, scale = 6) private BigDecimal averageTradePrice;
    @Enumerated(EnumType.STRING) @Column(name = "order_status", nullable = false, length = 24) private OrderStatus status;
    @Enumerated(EnumType.STRING) @Column(name = "target_status", nullable = false, length = 24) private OrderStatus targetStatus;
    @Column(nullable = false, length = 32) private String destination;
    @Column(name = "originator_reference", nullable = false, length = 100) private String originatorReference;
    @Column(name = "parent_order_id") private UUID parentOrderId;
    @Column(name = "root_order_id", nullable = false) private UUID rootOrderId;
    @Column(name = "execution_parameters") private String executionParameters;
    @Column(name = "error_message", length = 500) private String errorMessage;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

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
        this.id = id; this.userSubject = userSubject; this.deskId = deskId; this.listing = new ListingDetails(listing); this.side = side;
        this.type = type; this.quantity = quantity; this.limitPrice = limitPrice; this.remainingQuantity = quantity;
        this.tradedQuantity = BigDecimal.ZERO; this.status = OrderStatus.LIVE; this.targetStatus = OrderStatus.LIVE;
        this.destination = destination; this.originatorReference = originatorReference; this.parentOrderId = parentOrderId;
        this.rootOrderId = rootOrderId == null ? id : rootOrderId; this.executionParameters = executionParameters;
        this.createdAt = Instant.now(); this.updatedAt = this.createdAt;
        validateInvariants();
    }

    public synchronized void modify(BigDecimal newQuantity, BigDecimal newLimitPrice) {
        require(status == OrderStatus.LIVE || status == OrderStatus.PARTIALLY_FILLED,
                "Only active orders can be modified");
        require(targetStatus != OrderStatus.CANCELLED,
                "Orders pending cancellation cannot be modified");
        validateQuantity(newQuantity);
        require(newQuantity.compareTo(tradedQuantity) > 0,
                "Modified quantity must be greater than the quantity already traded");
        validatePrice(newLimitPrice);
        quantity = newQuantity;
        limitPrice = newLimitPrice;
        remainingQuantity = newQuantity.subtract(tradedQuantity);
        updatedAt = Instant.now();
        validateInvariants();
    }

    public synchronized void applyFill(BigDecimal fillQuantity, BigDecimal fillPrice) {
        require(status == OrderStatus.LIVE || status == OrderStatus.PARTIALLY_FILLED
                        || status == OrderStatus.CANCELLED,
                "Only active or cancelled orders can receive fills");
        require(fillQuantity != null && fillQuantity.signum() > 0,
                "Fill quantity must be greater than zero");
        require(fillQuantity.remainder(listing.getSizeIncrement()).signum() == 0,
                "Fill quantity must align with the listing size increment");
        require(fillQuantity.compareTo(remainingQuantity) <= 0,
                "Fill quantity cannot exceed remaining quantity");
        require(fillPrice != null && fillPrice.signum() > 0,
                "Fill price must be greater than zero");

        BigDecimal previousTradeValue = averageTradePrice == null
                ? BigDecimal.ZERO
                : averageTradePrice.multiply(tradedQuantity);
        BigDecimal newTradedQuantity = tradedQuantity.add(fillQuantity);
        BigDecimal newTradeValue = previousTradeValue.add(fillPrice.multiply(fillQuantity));

        tradedQuantity = newTradedQuantity;
        remainingQuantity = quantity.subtract(newTradedQuantity);
        averageTradePrice = newTradeValue.divide(newTradedQuantity, 6, RoundingMode.HALF_UP);
        if (remainingQuantity.signum() == 0) {
            status = OrderStatus.FILLED;
            targetStatus = OrderStatus.FILLED;
        } else if (status != OrderStatus.CANCELLED) {
            status = OrderStatus.PARTIALLY_FILLED;
            if (targetStatus != OrderStatus.CANCELLED) targetStatus = OrderStatus.LIVE;
        }
        updatedAt = Instant.now();
        validateInvariants();
    }

    public synchronized void requestCancel() {
        require(status == OrderStatus.LIVE || status == OrderStatus.PARTIALLY_FILLED,
                "Only active orders can be cancelled");
        require(targetStatus != OrderStatus.CANCELLED,
                "Order cancellation is already pending");
        targetStatus = OrderStatus.CANCELLED;
        updatedAt = Instant.now();
        validateInvariants();
    }

    public synchronized void confirmCancel() {
        require(status == OrderStatus.LIVE || status == OrderStatus.PARTIALLY_FILLED,
                "Only active orders can confirm cancellation");
        targetStatus = OrderStatus.CANCELLED;
        status = OrderStatus.CANCELLED;
        updatedAt = Instant.now();
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

    public synchronized void reject(String message) {
        require(status == OrderStatus.LIVE && tradedQuantity.signum() == 0,
                "Only unfilled live orders can be rejected");
        status = OrderStatus.REJECTED;
        targetStatus = OrderStatus.REJECTED;
        errorMessage = message == null || message.isBlank() ? "Execution venue rejected the order" : message;
        updatedAt = Instant.now();
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
        require(candidate != null && candidate.signum() > 0,
                "Quantity must be greater than zero");
        BigDecimal increment = listing == null ? null : listing.getSizeIncrement();
        require(increment != null && increment.signum() > 0,
                "Listing size increment must be greater than zero");
        require(candidate.remainder(increment).signum() == 0,
                "Quantity must align with the listing size increment");
    }

    private void validatePrice(BigDecimal candidate) {
        BigDecimal tickSize = listing == null ? null : listing.getTickSize();
        require(tickSize != null && tickSize.signum() > 0,
                "Listing tick size must be greater than zero");
        if (type == OrderType.MARKET) {
            require(candidate == null, "Market orders cannot have a limit price");
            return;
        }
        require(candidate != null && candidate.signum() > 0,
                "Limit orders require a positive limit price");
        require(candidate.remainder(tickSize).signum() == 0,
                "Limit price must align with the listing tick size");
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

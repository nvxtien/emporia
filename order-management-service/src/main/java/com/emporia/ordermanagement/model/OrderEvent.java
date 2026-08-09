package com.emporia.ordermanagement.model;

import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.time.DomainClock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;

@Entity
@Table(name = "order_event")
@Getter
public class OrderEvent {
    @Id
    private UUID id;
    @Column(name = "command_id", nullable = false)
    private UUID commandId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private TradingOrder order;
    @Column(name = "order_version", nullable = false)
    private long orderVersion;
    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;
    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false, length = 24)
    private OrderStatus status;
    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity;
    @Column(precision = 19, scale = 6)
    private BigDecimal price;
    @Column(length = 500) private
    String message;
    @Column(columnDefinition = "text")
    private String payload;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected OrderEvent() { }

    public OrderEvent(UUID commandId, TradingOrder order, String eventType, String message, String payload) {
        this.id = UUID.randomUUID(); this.commandId = commandId; this.order = order; this.orderVersion = order.getVersion();
        this.eventType = eventType; this.status = order.getStatus(); this.quantity = order.getQuantity(); this.price = order.getLimitPrice();
        this.message = message; this.payload = payload; this.occurredAt = DomainClock.now();
    }

    public OrderDomainEvent domainEvent() {
        return new OrderDomainEvent(SCHEMA_VERSION, id, commandId, order.getId(), order.getUserSubject(),
                order.getDeskId(), eventType,
                orderVersion, status, occurredAt, payload);
    }
}

package com.emporia.ordermanagement.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "execution")
@Setter
@Getter
public class Execution {
    @Id
    private UUID id;
    @Column(name = "execution_reference", nullable = false, unique = true, length = 100)
    private String executionReference;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private TradingOrder order;
    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity;
    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal price;
    @Column(nullable = false, length = 32)
    private String venue;
    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    protected Execution() {
    }

    public Execution(UUID id, String executionReference, TradingOrder order, BigDecimal quantity,
                     BigDecimal price, String venue, Instant executedAt) {
        this.id = id;
        this.executionReference = executionReference;
        this.order = order;
        this.quantity = quantity;
        this.price = price;
        this.venue = venue;
        this.executedAt = executedAt;
    }
}

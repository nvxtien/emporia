package com.emporia.ordermanagement.model;

import com.emporia.events.time.DomainClock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

@Entity
@Table(name = "order_outbox")
@Getter
public class OrderOutboxRecord {

    public enum PayloadType { ORDER_EVENT, ORDER_RESULT }

    public enum Status { PENDING, PUBLISHED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sequence_id")
    private Long sequenceId;
    @Column(nullable = false, length = 200)
    private String topic;
    @Column(name = "routing_key", nullable = false, length = 200)
    private String routingKey;
    @Enumerated(EnumType.STRING)
    @Column(name = "payload_type", nullable = false, length = 20)
    private PayloadType payloadType;
    @Column(columnDefinition = "text", nullable = false)
    private String payload;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "last_error", length = 2000)
    private String lastError;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "published_at")
    private Instant publishedAt;

    protected OrderOutboxRecord() {
    }

    public OrderOutboxRecord(String topic, String routingKey, PayloadType payloadType, String payload) {
        this.topic = topic;
        this.routingKey = routingKey;
        this.payloadType = payloadType;
        this.payload = payload;
        this.status = Status.PENDING;
        this.attemptCount = 0;
        this.createdAt = DomainClock.now();
    }

    public void markPublished(Instant publishedAt) {
        this.status = Status.PUBLISHED;
        this.publishedAt = publishedAt;
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.attemptCount++;
        this.lastError = error;
    }
}

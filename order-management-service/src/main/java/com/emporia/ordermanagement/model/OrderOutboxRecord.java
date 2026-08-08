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

/**
 * A row Debezium's outbox event router CDC-drains from Postgres's own WAL
 * into Kafka - not polled and status-tracked by the app itself. {@code payload}
 * is pre-encoded SBE bytes, byte-identical to what should land on the topic,
 * so the connector's pass-through needs no header/type-mapping trickery.
 */
@Entity
@Table(name = "order_outbox")
@Getter
public class OrderOutboxRecord {

    public enum PayloadType { ORDER_EVENT, ORDER_RESULT }

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
    @Column(columnDefinition = "bytea", nullable = false)
    private byte[] payload;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OrderOutboxRecord() {
    }

    public OrderOutboxRecord(String topic, String routingKey, PayloadType payloadType, byte[] payload) {
        this.topic = topic;
        this.routingKey = routingKey;
        this.payloadType = payloadType;
        this.payload = payload;
        this.createdAt = DomainClock.now();
    }
}

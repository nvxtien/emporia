package com.emporia.ordermanagement.model;

import com.emporia.events.TradingEvents.CommandType;
import com.emporia.events.TradingEvents.OrderCommand;
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
import java.util.UUID;

@Entity
@Table(name = "order_input_event")
@Getter
public class OrderInputEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sequence_id")
    private Long sequenceId;
    @Column(name = "command_id", nullable = false)
    private UUID commandId;
    @Enumerated(EnumType.STRING)
    @Column(name = "command_type", nullable = false, length = 16)
    private CommandType commandType;
    @Column(name = "user_subject", nullable = false, length = 200)
    private String userSubject;
    @Column(name = "desk_id", nullable = false, length = 100)
    private String deskId;
    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;
    @Column(columnDefinition = "text", nullable = false)
    private String payload;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected OrderInputEvent() {
    }

    public OrderInputEvent(OrderCommand command, String payload) {
        this.commandId = command.commandId();
        this.commandType = command.commandType();
        this.userSubject = command.userSubject();
        this.deskId = command.deskId();
        this.schemaVersion = command.schemaVersion();
        this.payload = payload;
        this.receivedAt = DomainClock.now();
    }
}
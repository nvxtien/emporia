package com.emporia.ordermanagement.model;

import com.emporia.events.TradingEvents.OrderCommandResult;
import com.emporia.events.time.DomainClock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;
import java.util.UUID;

import lombok.Getter;

@Entity
@Table(name = "processed_order_command")
@Getter
public class ProcessedCommand {
    @Id @Column(name = "command_id")
    private UUID commandId;
    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;
    @Column(nullable = false)
    private boolean success;
    @Column(name = "http_status", nullable = false)
    private int status;
    @Column(length = 500)
    private String detail;
    @Column(columnDefinition = "text")
    private String payload;
    @Transient
    private Object view;
    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedCommand() { }

    public ProcessedCommand(OrderCommandResult result) {
        this(result, null);
    }

    public ProcessedCommand(OrderCommandResult result, Object view) {
        commandId = result.commandId(); schemaVersion = result.schemaVersion(); success = result.success();
        status = result.status(); detail = result.detail(); payload = result.payload();
        this.view = view;
        processedAt = DomainClock.now();
    }

    public OrderCommandResult result() { return new OrderCommandResult(schemaVersion, commandId, success, status, detail, payload); }

    public Object view() { return view; }

    public void materializePayload(String serialized) {
        if (payload == null) payload = serialized;
    }
}

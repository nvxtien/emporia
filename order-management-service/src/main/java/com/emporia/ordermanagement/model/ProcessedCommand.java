package com.emporia.ordermanagement.model;

import com.emporia.events.TradingEvents.OrderCommandResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_order_command")
public class ProcessedCommand {
    @Id @Column(name = "command_id") private UUID commandId;
    @Column(name = "schema_version", nullable = false) private int schemaVersion;
    @Column(nullable = false) private boolean success;
    @Column(name = "http_status", nullable = false) private int status;
    @Column(length = 500) private String detail;
    @Column(columnDefinition = "text") private String payload;
    @Column(name = "processed_at", nullable = false) private Instant processedAt;

    protected ProcessedCommand() { }

    public ProcessedCommand(OrderCommandResult result) {
        commandId = result.commandId(); schemaVersion = result.schemaVersion(); success = result.success();
        status = result.status(); detail = result.detail(); payload = result.payload(); processedAt = Instant.now();
    }

    public OrderCommandResult result() { return new OrderCommandResult(schemaVersion, commandId, success, status, detail, payload); }
}

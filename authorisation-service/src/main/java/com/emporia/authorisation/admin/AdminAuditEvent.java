package com.emporia.authorisation.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "admin_audit_event")
class AdminAuditEvent {

    @Id
    private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_subject", nullable = false, length = 200)
    private String actorSubject;

    @Column(name = "actor_username", nullable = false, length = 200)
    private String actorUsername;

    @Column(name = "actor_desk", nullable = false, length = 100)
    private String actorDesk;

    @Column(nullable = false, length = 80)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 80)
    private String entityType;

    @Column(name = "entity_id", nullable = false, length = 120)
    private String entityId;

    @Column(nullable = false, length = 30)
    private String result;

    @Column(name = "request_id", length = 120)
    private String requestId;

    @Column(name = "before_json", columnDefinition = "text")
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "text")
    private String afterJson;

    @Column(name = "metadata_json", columnDefinition = "text")
    private String metadataJson;

    protected AdminAuditEvent() {
    }

    AdminAuditEvent(
            AdminAuditContext context,
            String action,
            String entityType,
            String entityId,
            String result,
            String beforeJson,
            String afterJson,
            String metadataJson
    ) {
        this.id = UUID.randomUUID();
        this.occurredAt = Instant.now();
        this.actorSubject = context.subject();
        this.actorUsername = context.username();
        this.actorDesk = context.desk();
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.result = result;
        this.requestId = context.requestId();
        this.beforeJson = beforeJson;
        this.afterJson = afterJson;
        this.metadataJson = metadataJson;
    }

    UUID getId() {
        return id;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }

    String getActorSubject() {
        return actorSubject;
    }

    String getActorUsername() {
        return actorUsername;
    }

    String getActorDesk() {
        return actorDesk;
    }

    String getAction() {
        return action;
    }

    String getEntityType() {
        return entityType;
    }

    String getEntityId() {
        return entityId;
    }

    String getResult() {
        return result;
    }

    String getRequestId() {
        return requestId;
    }

    String getBeforeJson() {
        return beforeJson;
    }

    String getAfterJson() {
        return afterJson;
    }

    String getMetadataJson() {
        return metadataJson;
    }
}

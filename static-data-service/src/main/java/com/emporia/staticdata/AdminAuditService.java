package com.emporia.staticdata;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
class AdminAuditService {
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final AdminAuditEventRepository events;
    private final ObjectMapper objectMapper;

    AdminAuditService(AdminAuditEventRepository events, ObjectMapper objectMapper) {
        this.events = events;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    AdminAuditPage list(AdminAuditFilter filter) {
        AdminAuditFilter resolved = filter == null ? AdminAuditFilter.empty() : filter;
        int page = Math.max(0, resolved.page());
        int size = Math.min(MAX_PAGE_SIZE, Math.max(1, resolved.size() == null ? DEFAULT_PAGE_SIZE : resolved.size()));
        Page<AdminAuditEvent> result = events.findForAdmin(
                freeText(resolved.actor()),
                exact(resolved.action()),
                exact(resolved.entityType()),
                freeText(resolved.entityId()),
                exact(resolved.result()),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt")));
        return AdminAuditPage.from(result);
    }

    void record(
            AdminAuditContext context,
            String action,
            String entityType,
            String entityId,
            Object before,
            Object after,
            Object metadata) {
        events.save(new AdminAuditEvent(
                context,
                exact(action),
                exact(entityType),
                StringUtils.hasText(entityId) ? entityId.strip() : "unknown",
                "SUCCESS",
                json(before),
                json(after),
                json(metadata)));
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize static-data audit event", exception);
        }
    }

    private static String freeText(String value) {
        return StringUtils.hasText(value) ? value.strip() : "";
    }

    private static String exact(String value) {
        return StringUtils.hasText(value) ? value.strip().toUpperCase(Locale.ROOT) : "";
    }

    record AdminAuditFilter(
            String actor,
            String action,
            String entityType,
            String entityId,
            String result,
            Integer page,
            Integer size) {
        static AdminAuditFilter empty() {
            return new AdminAuditFilter(null, null, null, null, null, 0, DEFAULT_PAGE_SIZE);
        }
    }

    record AdminAuditPage(
            List<AdminAuditView> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last) {
        static AdminAuditPage from(Page<AdminAuditEvent> page) {
            return new AdminAuditPage(
                    page.getContent().stream().map(AdminAuditView::from).toList(),
                    page.getNumber(),
                    page.getSize(),
                    page.getTotalElements(),
                    page.getTotalPages(),
                    page.isFirst(),
                    page.isLast());
        }
    }

    record AdminAuditView(
            UUID id,
            Instant occurredAt,
            String actorSubject,
            String actorUsername,
            String actorDesk,
            String action,
            String entityType,
            String entityId,
            String result,
            String requestId,
            String beforeJson,
            String afterJson,
            String metadataJson) {
        static AdminAuditView from(AdminAuditEvent event) {
            return new AdminAuditView(
                    event.getId(),
                    event.getOccurredAt(),
                    event.getActorSubject(),
                    event.getActorUsername(),
                    event.getActorDesk(),
                    event.getAction(),
                    event.getEntityType(),
                    event.getEntityId(),
                    event.getResult(),
                    event.getRequestId(),
                    event.getBeforeJson(),
                    event.getAfterJson(),
                    event.getMetadataJson());
        }
    }
}

package com.emporia.authorisation.admin;

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
public class AdminAuditService {
    static final String RESULT_SUCCESS = "SUCCESS";
    static final String ENTITY_USER = "USER";

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final AdminAuditEventRepository events;
    private final ObjectMapper objectMapper;

    AdminAuditService(AdminAuditEventRepository events, ObjectMapper objectMapper) {
        this.events = events;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public AdminAuditPage list(AdminAuditFilter filter) {
        AdminAuditFilter resolved = filter == null ? AdminAuditFilter.empty() : filter;
        int page = Math.max(0, resolved.page());
        int size = Math.min(MAX_PAGE_SIZE, Math.max(1, resolved.size() == null ? DEFAULT_PAGE_SIZE : resolved.size()));
        Page<AdminAuditEvent> result = events.findForAdmin(
                normalizeFreeText(resolved.actor()),
                normalizeExact(resolved.action()),
                normalizeExact(resolved.entityType()),
                normalizeFreeText(resolved.entityId()),
                normalizeExact(resolved.result()),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"))
        );
        return AdminAuditPage.from(result);
    }

    void recordUserEvent(AdminAuditContext context, String action, Object before, Object after, Object metadata) {
        record(
                context,
                action,
                ENTITY_USER,
                entityId(after, before),
                RESULT_SUCCESS,
                json(before),
                json(after),
                json(metadata)
        );
    }

    @Transactional
    void record(
            AdminAuditContext context,
            String action,
            String entityType,
            String entityId,
            String result,
            String beforeJson,
            String afterJson,
            String metadataJson
    ) {
        events.save(new AdminAuditEvent(
                context,
                normalizeExact(action),
                normalizeExact(entityType),
                StringUtils.hasText(entityId) ? entityId.strip() : "unknown",
                normalizeExact(result),
                beforeJson,
                afterJson,
                metadataJson
        ));
    }

    private static String entityId(Object primary, Object secondary) {
        if (primary instanceof AdminUserService.AdminUserView user) {
            return user.id().toString();
        }
        if (secondary instanceof AdminUserService.AdminUserView user) {
            return user.id().toString();
        }
        return "unknown";
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize an admin audit event", exception);
        }
    }

    private static String normalizeFreeText(String value) {
        return StringUtils.hasText(value) ? value.strip() : "";
    }

    private static String normalizeExact(String value) {
        return StringUtils.hasText(value) ? value.strip().toUpperCase(Locale.ROOT) : "";
    }

    public record AdminAuditFilter(
            String actor,
            String action,
            String entityType,
            String entityId,
            String result,
            Integer page,
            Integer size
    ) {
        static AdminAuditFilter empty() {
            return new AdminAuditFilter(null, null, null, null, null, 0, DEFAULT_PAGE_SIZE);
        }
    }

    public record AdminAuditPage(
            List<AdminAuditView> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last
    ) {
        static AdminAuditPage from(Page<AdminAuditEvent> page) {
            return new AdminAuditPage(
                    page.getContent().stream().map(AdminAuditView::from).toList(),
                    page.getNumber(),
                    page.getSize(),
                    page.getTotalElements(),
                    page.getTotalPages(),
                    page.isFirst(),
                    page.isLast()
            );
        }
    }

    public record AdminAuditView(
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
            String metadataJson
    ) {
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
                    event.getMetadataJson()
            );
        }
    }
}

package com.emporia.portfolio;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
class PortfolioAdminAuditService {
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final String FILTERS = """
            WHERE (? = ''
                    OR LOWER(actor_subject) LIKE CONCAT('%', ?, '%')
                    OR LOWER(actor_username) LIKE CONCAT('%', ?, '%'))
                AND (? = '' OR action = ?)
                AND (? = '' OR entity_type = ?)
                AND (? = ''
                    OR LOWER(entity_id) LIKE CONCAT('%', ?, '%'))
                AND (? = '' OR result = ?)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    PortfolioAdminAuditService(
            final JdbcTemplate jdbc,
            final ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    AdminAuditPage list(final AdminAuditFilter filter) {
        final AdminAuditFilter resolved =
                filter == null ? AdminAuditFilter.empty() : filter;
        final int page =
                Math.max(0, resolved.page() == null ? 0 : resolved.page());
        final int size =
                Math.min(
                        MAX_PAGE_SIZE,
                        Math.max(
                                1,
                                resolved.size() == null
                                        ? DEFAULT_PAGE_SIZE
                                        : resolved.size()));
        final Object[] filters = filterParameters(resolved);
        final Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM admin_audit_event " + FILTERS,
                        Long.class,
                        filters);
        final List<Object> parameters =
                new ArrayList<>(List.of(filters));
        parameters.add(size);
        parameters.add((long) page * size);
        final List<AdminAuditView> items =
                jdbc.query(
                        """
                        SELECT id,
                            occurred_at,
                            actor_subject,
                            actor_username,
                            actor_desk,
                            action,
                            entity_type,
                            entity_id,
                            result,
                            request_id,
                            before_json,
                            after_json,
                            metadata_json
                        FROM admin_audit_event
                        """
                                + FILTERS
                                + """
                        ORDER BY occurred_at DESC
                        LIMIT ? OFFSET ?
                        """,
                        (result, row) -> new AdminAuditView(
                                (UUID) result.getObject("id"),
                                result.getTimestamp("occurred_at").toInstant(),
                                result.getString("actor_subject"),
                                result.getString("actor_username"),
                                result.getString("actor_desk"),
                                result.getString("action"),
                                result.getString("entity_type"),
                                result.getString("entity_id"),
                                result.getString("result"),
                                result.getString("request_id"),
                                result.getString("before_json"),
                                result.getString("after_json"),
                                result.getString("metadata_json")),
                        parameters.toArray());
        return new AdminAuditPage(
                items,
                page,
                size,
                total == null ? 0 : total,
                totalPages(total == null ? 0 : total, size),
                page == 0,
                page + 1 >= totalPages(total == null ? 0 : total, size));
    }

    void record(
            final AdminAuditContext context,
            final String action,
            final String entityType,
            final String entityId,
            final Object before,
            final Object after,
            final Object metadata) {
        jdbc.update(
                """
                INSERT INTO admin_audit_event (
                    id,
                    occurred_at,
                    actor_subject,
                    actor_username,
                    actor_desk,
                    action,
                    entity_type,
                    entity_id,
                    result,
                    request_id,
                    before_json,
                    after_json,
                    metadata_json
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                Timestamp.from(Instant.now()),
                context.subject(),
                context.username(),
                context.desk(),
                exact(action),
                exact(entityType),
                StringUtils.hasText(entityId) ? entityId.strip() : "unknown",
                "SUCCESS",
                context.requestId(),
                json(before),
                json(after),
                json(metadata));
    }

    private Object[] filterParameters(final AdminAuditFilter filter) {
        final String actor = freeText(filter.actor());
        final String action = exact(filter.action());
        final String entityType = exact(filter.entityType());
        final String entityId = freeText(filter.entityId());
        final String result = exact(filter.result());
        return new Object[] {
                actor,
                actor,
                actor,
                action,
                action,
                entityType,
                entityType,
                entityId,
                entityId,
                result,
                result
        };
    }

    private String json(final Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (final Exception error) {
            throw new IllegalStateException(
                    "Could not serialize portfolio audit event",
                    error);
        }
    }

    private static int totalPages(
            final long totalElements,
            final int size) {
        return totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
    }

    private static String freeText(final String value) {
        return StringUtils.hasText(value)
                ? value.strip().toLowerCase(Locale.ROOT)
                : "";
    }

    private static String exact(final String value) {
        return StringUtils.hasText(value)
                ? value.strip().toUpperCase(Locale.ROOT)
                : "";
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
    }
}

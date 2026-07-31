package com.emporia.portfolio;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

record AdminAuditContext(String subject, String username, String desk, String requestId) {
    private static final String UNKNOWN = "unknown";

    static AdminAuditContext from(
            final Jwt jwt,
            final String requestId) {
        if (jwt == null) {
            return new AdminAuditContext(
                    UNKNOWN,
                    UNKNOWN,
                    UNKNOWN,
                    clean(requestId));
        }
        final String subject =
                value(jwt.getSubject(), UNKNOWN);
        return new AdminAuditContext(
                subject,
                value(jwt.getClaimAsString("preferred_username"), subject),
                value(jwt.getClaimAsString("desk"), UNKNOWN),
                clean(requestId));
    }

    private static String value(
            final String primary,
            final String fallback) {
        return StringUtils.hasText(primary) ? primary.strip() : fallback;
    }

    private static String clean(final String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}

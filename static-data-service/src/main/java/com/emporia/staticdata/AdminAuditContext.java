package com.emporia.staticdata;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

record AdminAuditContext(String subject, String username, String desk, String requestId) {
    private static final String UNKNOWN = "unknown";

    static AdminAuditContext from(Jwt jwt, String requestId) {
        if (jwt == null) {
            return new AdminAuditContext(UNKNOWN, UNKNOWN, UNKNOWN, clean(requestId));
        }
        String subject = value(jwt.getSubject(), UNKNOWN);
        String username = value(jwt.getClaimAsString("preferred_username"), subject);
        String desk = value(jwt.getClaimAsString("desk"), UNKNOWN);
        return new AdminAuditContext(subject, username, desk, clean(requestId));
    }

    private static String value(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary.strip() : fallback;
    }

    private static String clean(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}

package com.emporia.authorisation.admin;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;

record AdminAuditContext(String subject, String username, String desk, String requestId) {
    private static final String UNKNOWN = "unknown";

    static AdminAuditContext from(Authentication authentication, String requestId) {
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            Jwt jwt = jwtAuthentication.getToken();
            String subject = value(jwt.getSubject(), authentication.getName(), UNKNOWN);
            String username = value(jwt.getClaimAsString("preferred_username"), subject, UNKNOWN);
            String desk = value(jwt.getClaimAsString("desk"), UNKNOWN);
            return new AdminAuditContext(subject, username, desk, clean(requestId));
        }
        String name = authentication == null ? UNKNOWN : value(authentication.getName(), UNKNOWN);
        return new AdminAuditContext(name, name, UNKNOWN, clean(requestId));
    }

    private static String value(String primary, String fallback) {
        return value(primary, fallback, UNKNOWN);
    }

    private static String value(String primary, String secondary, String fallback) {
        if (StringUtils.hasText(primary)) {
            return primary.strip();
        }
        if (StringUtils.hasText(secondary)) {
            return secondary.strip();
        }
        return fallback;
    }

    private static String clean(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}

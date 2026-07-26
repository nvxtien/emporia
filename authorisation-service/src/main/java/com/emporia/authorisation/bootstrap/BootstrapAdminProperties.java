package com.emporia.authorisation.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("emporia.auth.bootstrap-admin")
public record BootstrapAdminProperties(
        boolean enabled,
        String username,
        String email,
        String password,
        String desk,
        boolean canTrade
) {
}

package com.emporia.ordermanagement;

import org.junit.jupiter.api.Test;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityConfigTest {

    @Test
    void securityConfigInstantiatesAndConfiguresFilterChain() throws Exception {
        SecurityConfig config = new SecurityConfig();
        assertThat(config).isNotNull();

        HttpSecurity http = mock(HttpSecurity.class, RETURNS_DEEP_STUBS);
        when(http.csrf(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            org.springframework.security.config.Customizer<org.springframework.security.config.annotation.web.configurers.CsrfConfigurer<HttpSecurity>> customizer = inv.getArgument(0);
            org.springframework.security.config.annotation.web.configurers.CsrfConfigurer<HttpSecurity> csrfConfig = mock(org.springframework.security.config.annotation.web.configurers.CsrfConfigurer.class);
            customizer.customize(csrfConfig);
            return http;
        });
        when(http.sessionManagement(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            org.springframework.security.config.Customizer<org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer<HttpSecurity>> customizer = inv.getArgument(0);
            org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer<HttpSecurity> sessionConfig = mock(org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer.class);
            customizer.customize(sessionConfig);
            return http;
        });
        when(http.authorizeHttpRequests(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            org.springframework.security.config.Customizer<org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry> customizer = inv.getArgument(0);
            org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer.AuthorizationManagerRequestMatcherRegistry registry = mock(org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer.AuthorizationManagerRequestMatcherRegistry.class, RETURNS_DEEP_STUBS);
            customizer.customize(registry);
            return http;
        });
        when(http.oauth2ResourceServer(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            org.springframework.security.config.Customizer<org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer<HttpSecurity>> customizer = inv.getArgument(0);
            org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer<HttpSecurity> serverConfig = mock(org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer.class, RETURNS_DEEP_STUBS);
            customizer.customize(serverConfig);
            return http;
        });
        DefaultSecurityFilterChain chain = mock(DefaultSecurityFilterChain.class);
        when(http.build()).thenReturn(chain);

        SecurityFilterChain result = config.securityFilterChain(http);
        assertThat(result).isNotNull();
    }
}

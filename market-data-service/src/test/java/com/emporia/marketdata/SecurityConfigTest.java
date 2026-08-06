package com.emporia.marketdata;

import org.junit.jupiter.api.Test;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityConfigTest {

    @Test
    void configuresSecurityFilterChain() throws Exception {
        HttpSecurity http = mock(HttpSecurity.class, RETURNS_DEEP_STUBS);
        org.springframework.security.web.DefaultSecurityFilterChain expectedChain = mock(org.springframework.security.web.DefaultSecurityFilterChain.class);

        when(http.csrf(any())).thenReturn(http);
        when(http.sessionManagement(any())).thenReturn(http);
        when(http.authorizeHttpRequests(any())).thenReturn(http);
        when(http.oauth2ResourceServer(any())).thenReturn(http);
        when(http.build()).thenReturn(expectedChain);

        SecurityConfig config = new SecurityConfig();
        SecurityFilterChain result = config.securityFilterChain(http);

        assertThat(result).isEqualTo(expectedChain);
    }
}

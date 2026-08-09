package com.emporia.authentication.config;

import com.emporia.authentication.user.UserAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {
    private static final String EXECUTION_CLIENT_ID_PROPERTY =
            "${spring.security.oauth2.authorizationserver.client.emporia-execution.registration.client-id:"
                    + "emporia-execution}";
    private static final String EXECUTION_RECOVERY_ROLE = "ROLE_EXECUTION_SERVICE";

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/.well-known/**", config);
        source.registerCorsConfiguration("/oauth2/**", config);
        source.registerCorsConfiguration("/userinfo", config);
        source.registerCorsConfiguration("/connect/logout", config);
        source.registerCorsConfiguration("/auth/csrf", config);
        source.registerCorsConfiguration("/admin/**", config);
        return source;
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> tradingIdentityClaims(
            UserAccountRepository users,
            @Value(EXECUTION_CLIENT_ID_PROPERTY) String executionClientId) {
        return context -> {
            if (AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType())) {
                if (executionClientId.equals(context.getRegisteredClient().getClientId())) {
                    context.getClaims().claim("authorities", List.of(EXECUTION_RECOVERY_ROLE));
                }
                return;
            }
            String username = context.getPrincipal().getName();
            users.findByUsernameIgnoreCase(username).ifPresent(account -> context.getClaims()
                    .claim("preferred_username", account.getUsername())
                    .claim("desk", account.getDesk())
                    .claim("can_trade", account.canTrade())
                    .claim("tier", account.getTier().name().toLowerCase(java.util.Locale.ROOT))
                    .claim("authorities", account.getAuthorities().stream().map(Enum::name).sorted().toList()));
        };
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    AuthenticationFailureHandler loginFailureHandler() {
        SimpleUrlAuthenticationFailureHandler invalidCredentials =
                new SimpleUrlAuthenticationFailureHandler("/login?error");
        SimpleUrlAuthenticationFailureHandler disabledAccount =
                new SimpleUrlAuthenticationFailureHandler("/login?disabled");
        return (request, response, exception) -> {
            if (containsDisabledException(exception)) {
                disabledAccount.onAuthenticationFailure(request, response, exception);
                return;
            }
            invalidCredentials.onAuthenticationFailure(request, response, exception);
        };
    }

    @Bean
    Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopeAuthorities = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>(scopeAuthorities.convert(jwt));
            authorities.addAll(authorities(jwt.getClaim("authorities")));
            return authorities;
        });
        return converter;
    }

    private static Collection<GrantedAuthority> authorities(Object claim) {
        if (claim instanceof Collection<?> values) {
            return values.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(SimpleGrantedAuthority::new)
                    .map(GrantedAuthority.class::cast)
                    .toList();
        }
        if (claim instanceof String value && !value.isBlank()) {
            return List.of(new SimpleGrantedAuthority(value));
        }
        return List.of();
    }

    private static boolean containsDisabledException(AuthenticationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof DisabledException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter
    ) throws Exception {
        http.cors(Customizer.withDefaults());
        http.oauth2AuthorizationServer(authorizationServer -> {
            http.securityMatcher(authorizationServer.getEndpointsMatcher());
            authorizationServer.oidc(Customizer.withDefaults());
        });
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/login").permitAll()
                .anyRequest().authenticated()
        );
        http.oauth2ResourceServer(resourceServer ->
                resourceServer.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));

        http.exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"),
                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
        ));

        return http.build();
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 5)
    SecurityFilterChain applicationSecurityFilterChain(
            HttpSecurity http,
            Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter,
            AuthenticationFailureHandler loginFailureHandler
    ) throws Exception {
        http.cors(Customizer.withDefaults());
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/admin/users/**", "/admin/audit/**"));

        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(new MediaTypeRequestMatcher(MediaType.TEXT_HTML));
        http.requestCache(cache -> cache.requestCache(requestCache));

        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health", "/actuator/health/**", "/auth/csrf", "/login", "/error", "/default-ui.css", "/favicon.ico").permitAll()
                .requestMatchers("/admin/users/**", "/admin/audit/**").hasRole("ADMIN")
                .anyRequest().authenticated()
        );
        http.formLogin(form -> form
                .loginPage("/login")
                .failureHandler(loginFailureHandler)
                .permitAll());
        http.oauth2ResourceServer(resourceServer ->
                resourceServer.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }
}

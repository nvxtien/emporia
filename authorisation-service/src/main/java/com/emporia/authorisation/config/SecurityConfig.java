package com.emporia.authorisation.config;

import com.emporia.authorisation.user.UserAccountRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

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
        return source;
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> tradingIdentityClaims(UserAccountRepository users) {
        return context -> {
            String username = context.getPrincipal().getName();
            users.findByUsernameIgnoreCase(username).ifPresent(account -> context.getClaims()
                    .claim("preferred_username", account.getUsername())
                    .claim("desk", account.getDesk())
                    .claim("can_trade", account.canTrade())
                    .claim("authorities", account.getAuthorities().stream().map(Enum::name).sorted().toList()));
        };
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        http.cors(Customizer.withDefaults());
        http.oauth2AuthorizationServer(authorizationServer -> {
            http.securityMatcher(authorizationServer.getEndpointsMatcher());
            authorizationServer.oidc(Customizer.withDefaults());
        });
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/login").permitAll()
                .anyRequest().authenticated()
        );
        http.oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));

        http.exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"),
                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
        ));

        return http.build();
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 5)
    SecurityFilterChain applicationSecurityFilterChain(HttpSecurity http) throws Exception {
        http.cors(Customizer.withDefaults());

        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(new MediaTypeRequestMatcher(MediaType.TEXT_HTML));
        http.requestCache(cache -> cache.requestCache(requestCache));

        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health", "/actuator/health/**", "/auth/csrf", "/login", "/error", "/default-ui.css", "/favicon.ico").permitAll()
                .anyRequest().authenticated()
        );
        http.formLogin(form -> form.loginPage("/login").permitAll());
        return http.build();
    }
}

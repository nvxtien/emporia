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
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.util.Set;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

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
        http.oauth2AuthorizationServer(authorizationServer -> {
            http.securityMatcher(authorizationServer.getEndpointsMatcher());
            authorizationServer.oidc(Customizer.withDefaults());
        });
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
        http.oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));

        MediaTypeRequestMatcher htmlRequest = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
        htmlRequest.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        http.exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/sign-in"),
                htmlRequest
        ));

        return http.build();
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 5)
    SecurityFilterChain applicationSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health", "/actuator/health/**", "/auth/csrf", "/sign-in").permitAll()
                .anyRequest().authenticated()
        );
        http.formLogin(form -> form
                .loginPage("/sign-in")
                .loginProcessingUrl("/login")
                .failureUrl("/sign-in?error")
                .permitAll()
        );
        return http.build();
    }
}

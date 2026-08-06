package com.emporia.gateway.filter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OrderRateLimiterGatewayFilterFactory
        extends AbstractGatewayFilterFactory<OrderRateLimiterGatewayFilterFactory.Config> {

    private static final Duration REFILL_PERIOD = Duration.ofSeconds(1);
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final String bypassServiceAccountClaim;
    private final Set<String> bypassAuthorities;
    private final Set<String> bypassClaims;
    private final Set<String> bypassRemoteAddresses;
    private final Counter rateLimited;
    private final Counter bypassed;

    public OrderRateLimiterGatewayFilterFactory(MeterRegistry meters,
                                                @Value("${emporia.gateway.order-rate-limiter.bypass-service-account-claim:service_account=true}") String bypassServiceAccountClaim,
                                                @Value("${emporia.gateway.order-rate-limiter.bypass-authorities:}") String bypassAuthorities,
                                                @Value("${emporia.gateway.order-rate-limiter.bypass-claims:}") String bypassClaims,
                                                @Value("${emporia.gateway.order-rate-limiter.bypass-remote-addresses:}") String bypassRemoteAddresses) {
        super(Config.class);
        this.bypassServiceAccountClaim = bypassServiceAccountClaim == null ? "" : bypassServiceAccountClaim.trim();
        this.bypassAuthorities = csvToSet(bypassAuthorities);
        this.bypassClaims = csvToSet(bypassClaims);
        this.bypassRemoteAddresses = csvToSet(bypassRemoteAddresses);
        this.rateLimited = meters.counter("emporia.gateway.orders.rate_limited");
        this.bypassed = meters.counter("emporia.gateway.orders.rate_limiter_bypassed");
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> exchange.getPrincipal()
                .cast(Authentication.class)
                .map(authentication -> new Identity(identity(authentication, exchange), authentication))
                .switchIfEmpty(Mono.fromSupplier(() -> new Identity(remoteAddress(exchange), null)))
                .flatMap(identity -> {
                    if (isBypassed(identity, remoteAddress(exchange))) {
                        bypassed.increment();
                        return chain.filter(exchange);
                    }
                    long replenishRate = getReplenishRate(identity, config);
                    long burstCapacity = getBurstCapacity(identity, config);
                    TokenBucket bucket = buckets.computeIfAbsent(identity.key(),
                            ignored -> new TokenBucket(burstCapacity, burstCapacity, System.nanoTime()));
                    if (bucket.tryConsume(replenishRate, burstCapacity, config.getRequestedTokens())) {
                        return chain.filter(exchange);
                    }
                    rateLimited.increment();
                    byte[] payload = "gateway_rate_limited: too many order commands".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    exchange.getResponse().getHeaders().setContentType(MediaType.TEXT_PLAIN);
                    exchange.getResponse().getHeaders().set("X-RateLimit-Reason", "gateway-order-rate-limit");
                    exchange.getResponse().getHeaders().set("Retry-After", "1");
                    return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(payload)));
                });
    }

    private long getReplenishRate(Identity identity, Config config) {
        String tier = getTierClaim(identity);
        if ("institutional".equalsIgnoreCase(tier)) {
            return 5000L;
        } else if ("retail".equalsIgnoreCase(tier)) {
            return 100L;
        }
        return config.getReplenishRate();
    }

    private long getBurstCapacity(Identity identity, Config config) {
        String tier = getTierClaim(identity);
        if ("institutional".equalsIgnoreCase(tier)) {
            return 10000L;
        } else if ("retail".equalsIgnoreCase(tier)) {
            return 200L;
        }
        return config.getBurstCapacity();
    }

    private String getTierClaim(Identity identity) {
        if (identity != null && identity.authentication() instanceof JwtAuthenticationToken jwt) {
            Object claim = jwt.getToken().getClaims().get("tier");
            if (claim != null) {
                return claim.toString();
            }
        }
        return "";
    }

    private String identity(Authentication authentication, ServerWebExchange exchange) {
        if (authentication instanceof JwtAuthenticationToken jwt && jwt.getToken() != null) {
            return jwt.getToken().getSubject();
        }
        return authentication == null ? remoteAddress(exchange) : authentication.getName();
    }

    private String remoteAddress(ServerWebExchange exchange) {
        InetSocketAddress address = exchange.getRequest().getRemoteAddress();
        return address == null || address.getAddress() == null ? "anonymous" : address.getAddress().getHostAddress();
    }

    private boolean isBypassed(Identity identity, String remoteAddress) {
        if (bypassRemoteAddresses.contains(remoteAddress)) {
            return true;
        }
        if (identity.authentication() instanceof JwtAuthenticationToken jwt) {
            if (claimMatches(jwt, bypassServiceAccountClaim)) {
                return true;
            }
            if (!Collections.disjoint(jwtAuthorities(jwt), bypassAuthorities)) {
                return true;
            }
            return bypassClaims.stream().anyMatch(configured -> claimMatches(jwt, configured));
        }
        return false;
    }

    private static Set<String> csvToSet(String value) {
        if (!StringUtils.hasText(value)) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .forEach(result::add);
        return Set.copyOf(result);
    }

    private static List<String> jwtAuthorities(JwtAuthenticationToken jwt) {
        Object claim = jwt.getToken().getClaims().get("authorities");
        if (claim instanceof Collection<?> collection) {
            return collection.stream().filter(item -> item != null).map(Object::toString).toList();
        }
        if (claim instanceof String text) {
            return Arrays.stream(text.split("[,\\s]+"))
                    .filter(StringUtils::hasText)
                    .toList();
        }
        return List.of();
    }

    private static boolean claimMatches(JwtAuthenticationToken jwt, String configured) {
        int separator = configured.indexOf('=');
        if (separator <= 0 || separator == configured.length() - 1) {
            return false;
        }
        String claimName = configured.substring(0, separator).trim();
        String expectedValue = configured.substring(separator + 1).trim();
        Object value = jwt.getToken().getClaims().get(claimName);
        if (value instanceof Collection<?> collection) {
            return collection.stream().filter(item -> item != null).map(Object::toString).anyMatch(expectedValue::equals);
        }
        return value != null && expectedValue.equals(value.toString());
    }

    private record Identity(String key, Authentication authentication) {
    }

    public static class Config {
        private long replenishRate = 20;
        private long burstCapacity = 40;
        private long requestedTokens = 1;

        public long getReplenishRate() {
            return replenishRate;
        }

        public void setReplenishRate(long replenishRate) {
            this.replenishRate = replenishRate;
        }

        public long getBurstCapacity() {
            return burstCapacity;
        }

        public void setBurstCapacity(long burstCapacity) {
            this.burstCapacity = burstCapacity;
        }

        public long getRequestedTokens() {
            return requestedTokens;
        }

        public void setRequestedTokens(long requestedTokens) {
            this.requestedTokens = requestedTokens;
        }
    }

    private static final class TokenBucket {
        private long tokens;
        private long lastRefillNanos;

        private TokenBucket(long tokens, long burstCapacity, long lastRefillNanos) {
            this.tokens = Math.min(tokens, burstCapacity);
            this.lastRefillNanos = lastRefillNanos;
        }

        private synchronized boolean tryConsume(long replenishRate, long burstCapacity, long requestedTokens) {
            refill(replenishRate, burstCapacity);
            if (tokens < requestedTokens) {
                return false;
            }
            tokens -= requestedTokens;
            return true;
        }

        private void refill(long replenishRate, long burstCapacity) {
            long now = System.nanoTime();
            long elapsedNanos = now - lastRefillNanos;
            if (elapsedNanos <= 0) {
                return;
            }
            long refillTokens = (elapsedNanos * replenishRate) / REFILL_PERIOD.toNanos();
            if (refillTokens <= 0) {
                return;
            }
            tokens = Math.min(burstCapacity, tokens + refillTokens);
            lastRefillNanos = now;
        }
    }
}
package com.emporia.ordercommand;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Publishes reply-listener readiness so callers can wait for it instead of
 * discovering the gap by receiving a 504.
 *
 * <p>Registered into the readiness group, which is what
 * {@code /actuator/health/readiness} reports. The plain {@code /actuator/health}
 * endpoint goes UP as soon as the web layer is up — several seconds before this
 * service can actually answer an order submission — so the startup scripts wait
 * on the readiness probe rather than on liveness.
 */
@Component("replyListener")
class ReplyListenerHealthIndicator implements HealthIndicator {
    private final ReplyListenerReadiness readiness;

    ReplyListenerHealthIndicator(ReplyListenerReadiness readiness) {
        this.readiness = readiness;
    }

    @Override
    public Health health() {
        return readiness.ready()
                ? Health.up().withDetail("partitions", "assigned").build()
                : Health.down().withDetail("partitions", "awaiting assignment").build();
    }
}

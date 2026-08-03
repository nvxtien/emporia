package com.emporia.ordercommand;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks whether the order-results reply listener holds its partitions.
 *
 * <p>Order submission is synchronous request/reply over Kafka, and the reply
 * listener joins a brand-new consumer group on every start (the group id
 * carries a random uuid so that every instance receives every reply rather than
 * splitting them between instances). Because the group is new and
 * {@code auto-offset-reset} is {@code latest}, a reply written before the group
 * finishes joining is positioned past and therefore <em>skipped permanently</em>
 * rather than delivered late.
 *
 * <p>Measured on this stack that window is about 2.7 seconds wide, and the HTTP
 * port accepts orders throughout it. A command submitted then is processed and
 * persisted normally, but its reply is never read, so the caller receives a 504
 * after the full command timeout for an order that actually succeeded.
 *
 * <p>{@link KafkaCommandGateway} updates this via {@code ConsumerSeekAware} and
 * refuses to publish until it reports ready. Revocation is tracked as well as
 * assignment, because once partitions are gone the same window reopens.
 *
 * <p>State lives here rather than in the gateway so it can be asserted directly
 * in tests and published as a health indicator without starting a broker.
 */
@Component
class ReplyListenerReadiness {
    private final AtomicBoolean assigned = new AtomicBoolean(false);

    void markAssigned() {
        assigned.set(true);
    }

    void markRevoked() {
        assigned.set(false);
    }

    boolean ready() {
        return assigned.get();
    }
}

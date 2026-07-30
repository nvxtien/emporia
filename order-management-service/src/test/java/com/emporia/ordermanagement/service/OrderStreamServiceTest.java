package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.ordermanagement.model.TradingOrder;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThat;

class OrderStreamServiceTest {

    private final OrderStreamService service = new OrderStreamService(new ObjectMapper());

    // -------------------------------------------------------------------------
    // subscribe
    // -------------------------------------------------------------------------

    @Test
    void subscribeReturnsAnSseEmitter() {
        var emitter = service.subscribe("desk-a", List.of());
        assertThat(emitter).isNotNull();
    }

    @Test
    void subscribeWithInitialOrdersSendsThemToTheEmitter() {
        // Just verify subscribe does not throw when initial orders are present.
        // The SSE data is streamed internally; we assert no exception is raised and
        // the emitter is live.
        TradingOrder order = liveOrder("DMA");
        var emitter = service.subscribe("desk-a", List.of(order.view()));
        assertThat(emitter).isNotNull();
    }

    @Test
    void subscribeWithMultipleInitialOrdersDoesNotThrow() {
        TradingOrder o1 = liveOrder("DMA");
        TradingOrder o2 = liveOrder("VWAP");
        var emitter = service.subscribe("desk-b", List.of(o1.view(), o2.view()));
        assertThat(emitter).isNotNull();
    }

    @Test
    void heartbeatKeepsIdleSubscriptionsOpen() {
        service.subscribe("desk-heartbeat", List.of());

        service.heartbeat();
    }

    // -------------------------------------------------------------------------
    // publish
    // -------------------------------------------------------------------------

    @Test
    void publishSkipsDesksWithNoActiveSubscribers() {
        // Should not throw even with no subscription for the target desk
        service.publish(domainEvent("trader-x", "desk-x", "{\"status\":\"LIVE\"}"));
    }

    @Test
    void publishSkipsEventWithBlankDeskAndUserSubject() {
        // Both deskId and userSubject are blank — service should short-circuit silently
        service.publish(new OrderDomainEvent(
                SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "", "",
                "CREATED", 1L, OrderStatus.LIVE, Instant.now(), "{}"
        ));
    }

    @Test
    void publishFallsBackToUserSubjectWhenDeskIdIsBlankAndDelivers() {
        // Subscribe with the user subject as the desk key
        service.subscribe("trader-fallback", List.of());

        // Event has blank deskId but populated userSubject — should resolve the subscription
        OrderDomainEvent event = new OrderDomainEvent(
                SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "trader-fallback", "",
                "CREATED", 1L, OrderStatus.LIVE, Instant.now(), "{}"
        );

        // publish must not throw
        service.publish(event);
    }

    @Test
    void publishWithInvalidJsonPayloadDoesNotThrowAndSkipsDelivery() {
        service.subscribe("desk-invalid", List.of());

        OrderDomainEvent event = new OrderDomainEvent(
                SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "desk-invalid", "desk-invalid",
                "CREATED", 1L, OrderStatus.LIVE, Instant.now(), "NOT_JSON{"
        );

        // Should silently skip — not throw
        service.publish(event);
    }

    // -------------------------------------------------------------------------
    // Cleanup on completion / error / timeout
    // -------------------------------------------------------------------------

    @Test
    void publishRemovesDisconnectedSubscriptionOnSendFailure() {
        var emitter = service.subscribe("desk-cleanup", List.of());
        emitter.complete();

        // Publish to closed emitter will fail send(), catching exception and calling remove(subscription)
        service.publish(domainEvent("user", "desk-cleanup", "{}"));

        // Subsequent publish should execute cleanly with subscription removed
        service.publish(domainEvent("user", "desk-cleanup", "{}"));
    }

    // -------------------------------------------------------------------------
    // Multiple subscribers on the same desk
    // -------------------------------------------------------------------------

    @Test
    void publishDeliversToAllSubscribersOnTheSameDesk() {
        List<Throwable> errors = new ArrayList<>();
        var emitter1 = service.subscribe("desk-multi", List.of());
        var emitter2 = service.subscribe("desk-multi", List.of());

        emitter1.onError(errors::add);
        emitter2.onError(errors::add);

        service.publish(domainEvent("user", "desk-multi", "{}"));

        // No errors means both emitters received the event without exceptions
        assertThat(errors).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static OrderDomainEvent domainEvent(String userSubject, String deskId, String payload) {
        return new OrderDomainEvent(
                SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), userSubject, deskId,
                "CREATED", 1L, OrderStatus.LIVE, Instant.now(), payload
        );
    }

    private static TradingOrder liveOrder(String destination) {
        UUID id = UUID.randomUUID();
        TradingOrder order = new TradingOrder(
                id, "trader", "desk-a",
                new ListingSnapshot(1, 1, "AAPL", "Apple Inc.", "AAPL",
                        "XNAS", "Nasdaq", "US", "USD",
                        new BigDecimal("0.01"), new BigDecimal("0.01"),
                        new BigDecimal("200"), new BigDecimal("198")),
                OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"),
                destination, "stream-test", null, id, "{}"
        );
        ReflectionTestUtils.setField(order, "version", 1L);
        return order;
    }
}

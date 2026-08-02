package com.emporia.ordercommand;

import com.emporia.events.TradingEvents.CancelAllView;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the {@code emporia.order.submit} and {@code emporia.risk.check}
 * observations from REWORK_NOTE Phase 1_1.
 *
 * <p>The failure paths matter most: a timer that only records successes hides
 * exactly the latency that matters, so every case below asserts the outcome tag
 * rather than just the count.
 */
class OrderCommandObservationTest {
    private final StaticDataClient staticData = mock(StaticDataClient.class);
    private final KafkaCommandGateway commands = mock(KafkaCommandGateway.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry observations = ObservationRegistry.create();
    private OrderCommandController controller;

    @BeforeEach
    void setUp() {
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        controller = new OrderCommandController(staticData, commands, objectMapper, observations);
    }

    @Test
    void recordsSubmitAndAllowedRiskCheckOnSuccess() throws Exception {
        ListingSnapshot listing = listing();
        when(staticData.get(1L, "Bearer token")).thenReturn(listing);
        when(commands.send(any())).thenReturn(objectMapper.writeValueAsString(view(listing)));

        controller.create(jwt(true), "Bearer token", request("SMART"));

        assertThat(timerCount("emporia.order.submit", "operation", "create", "destination", "smart",
                "outcome", "success")).isEqualTo(1);
        assertThat(timerCount("emporia.risk.check", "decision", "allow", "reason", "ok")).isEqualTo(1);
    }

    @Test
    void recordsDeniedRiskCheckAndRejectedSubmitWhenPermissionIsMissing() {
        assertThatThrownBy(() -> controller.create(jwt(false), "Bearer token", request("DMA")))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(timerCount("emporia.risk.check", "decision", "deny", "reason", "permission")).isEqualTo(1);
        assertThat(timerCount("emporia.order.submit", "operation", "create", "destination", "dma",
                "outcome", "rejected")).isEqualTo(1);
    }

    @Test
    void recordsTimeoutOutcomeWhenTheOrderProcessorDoesNotAnswer() {
        when(staticData.get(1L, "Bearer token")).thenReturn(listing());
        when(commands.send(any())).thenThrow(
                new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "no answer"));

        assertThatThrownBy(() -> controller.create(jwt(true), "Bearer token", request("DMA")))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(timerCount("emporia.order.submit", "operation", "create", "destination", "dma",
                "outcome", "timeout")).isEqualTo(1);
    }

    @Test
    void tagsAnUnknownDestinationAsNoneRatherThanFailingToRecord() {
        when(staticData.get(1L, "Bearer token")).thenReturn(listing());

        assertThatThrownBy(() -> controller.create(jwt(true), "Bearer token", request("ICEBERG")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(timerCount("emporia.order.submit", "operation", "create", "destination", "none",
                "outcome", "rejected")).isEqualTo(1);
    }

    @Test
    void tagsCancelAllWithoutADestination() throws Exception {
        when(commands.send(any())).thenReturn(objectMapper.writeValueAsString(new CancelAllView(0)));

        controller.cancelAll(jwt(true));

        assertThat(timerCount("emporia.order.submit", "operation", "cancel_all", "destination", "none",
                "outcome", "success")).isEqualTo(1);
    }

    private long timerCount(String name, String... tags) {
        return meters.find(name).tags(tags).timer() == null
                ? 0 : meters.find(name).tags(tags).timer().count();
    }

    private static OrderCommandController.CreateOrderRequest request(String destination) {
        return new OrderCommandController.CreateOrderRequest(1L, OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("100"), new BigDecimal("150.00"), destination, "ref", null, Map.of());
    }

    private static Jwt jwt(boolean canTrade) {
        return Jwt.withTokenValue("token").header("alg", "none")
                .subject("trader-1")
                .claim("can_trade", canTrade)
                .claim("desk", "DESK-A")
                .build();
    }

    private static ListingSnapshot listing() {
        return new ListingSnapshot(1L, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("200"), new BigDecimal("198"));
    }

    private static OrderView view(ListingSnapshot listing) {
        UUID id = UUID.randomUUID();
        return new OrderView(id, 1L, "trader-1", "DESK-A", listing, OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("100"), new BigDecimal("150.00"), new BigDecimal("100"), BigDecimal.ZERO,
                BigDecimal.ZERO, OrderStatus.LIVE, OrderStatus.LIVE, "DMA", "ref-1", null, id, null, null,
                Instant.now(), Instant.now());
    }
}

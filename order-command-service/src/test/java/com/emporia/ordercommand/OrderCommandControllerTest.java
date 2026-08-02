package com.emporia.ordercommand;

import com.emporia.events.TradingEvents.CancelAllView;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCommandControllerTest {
    private final StaticDataClient staticData = mock(StaticDataClient.class);
    private final KafkaCommandGateway commands = mock(KafkaCommandGateway.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry observations = ObservationRegistry.create();
    private OrderCommandController controller;

    @BeforeEach
    void setUp() {
        // Wiring a meter handler turns observations into timers, so the tests
        // can assert on them exactly like OrderMetricsTest does.
        observations.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meters));
        controller = new OrderCommandController(staticData, commands, objectMapper, observations);
    }

    @Test
    void createOrderSuccess() throws Exception {
        Jwt jwt = jwt("trader-1", true, "DESK-A");
        ListingSnapshot listing = listing();
        when(staticData.get(1L, "Bearer token")).thenReturn(listing);

        UUID orderId = UUID.randomUUID();
        OrderView view = sampleOrderView(orderId, listing, OrderStatus.LIVE);
        when(commands.send(any())).thenReturn(objectMapper.writeValueAsString(view));

        OrderCommandController.CreateOrderRequest request = new OrderCommandController.CreateOrderRequest(
                1L, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("100"), new BigDecimal("150.00"),
                "DMA", "ref-123", null, Map.of());

        OrderView result = controller.create(jwt, "Bearer token", request);
        assertThat(result).isNotNull();

        ArgumentCaptor<OrderCommand> captor = ArgumentCaptor.forClass(OrderCommand.class);
        verify(commands).send(captor.capture());
        OrderCommand sent = captor.getValue();
        assertThat(sent.userSubject()).isEqualTo("trader-1");
        assertThat(sent.deskId()).isEqualTo("DESK-A");
        assertThat(sent.destination()).isEqualTo("DMA");
    }

    @Test
    void createOrderDefaultDestinationAndDeskFallback() throws Exception {
        Jwt jwt = jwt("trader-2", true, "");
        ListingSnapshot listing = listing();
        when(staticData.get(1L, "Bearer token")).thenReturn(listing);

        UUID orderId = UUID.randomUUID();
        OrderView view = sampleOrderView(orderId, listing, OrderStatus.LIVE);
        when(commands.send(any())).thenReturn(objectMapper.writeValueAsString(view));

        OrderCommandController.CreateOrderRequest request = new OrderCommandController.CreateOrderRequest(
                1L, OrderSide.SELL, OrderType.MARKET, new BigDecimal("50"), null,
                null, null, null, null);

        OrderView result = controller.create(jwt, "Bearer token", request);
        assertThat(result).isNotNull();

        ArgumentCaptor<OrderCommand> captor = ArgumentCaptor.forClass(OrderCommand.class);
        verify(commands).send(captor.capture());
        OrderCommand sent = captor.getValue();
        assertThat(sent.deskId()).isEqualTo("trader-2");
        assertThat(sent.destination()).isEqualTo("DMA");
        assertThat(sent.originatorReference()).isNotBlank();
    }

    @Test
    void createOrderRejectsNonTrader() {
        Jwt jwt = jwt("user-1", false, "DESK-A");
        OrderCommandController.CreateOrderRequest request = new OrderCommandController.CreateOrderRequest(
                1L, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("10"), new BigDecimal("100"),
                "DMA", "ref", null, null);

        assertThatThrownBy(() -> controller.create(jwt, "Bearer token", request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Trading permission is required");
    }

    @Test
    void createOrderRejectsInvalidDestination() {
        Jwt jwt = jwt("trader-1", true, "DESK-A");
        when(staticData.get(1L, "Bearer token")).thenReturn(listing());

        OrderCommandController.CreateOrderRequest request = new OrderCommandController.CreateOrderRequest(
                1L, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("10"), new BigDecimal("100"),
                "INVALID_DEST", "ref", null, null);

        assertThatThrownBy(() -> controller.create(jwt, "Bearer token", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Destination must be DMA, SMART, or VWAP");
    }

    @Test
    void createOrderHandlesBadGatewayJson() {
        Jwt jwt = jwt("trader-1", true, "DESK-A");
        when(staticData.get(1L, "Bearer token")).thenReturn(listing());
        when(commands.send(any())).thenReturn("INVALID_JSON");

        OrderCommandController.CreateOrderRequest request = new OrderCommandController.CreateOrderRequest(
                1L, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("10"), new BigDecimal("100"),
                "DMA", "ref", null, null);

        assertThatThrownBy(() -> controller.create(jwt, "Bearer token", request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Order processor returned invalid JSON");
    }

    @Test
    void modifyOrderSuccess() throws Exception {
        Jwt jwt = jwt("trader-1", true, "DESK-A");
        UUID orderId = UUID.randomUUID();
        OrderView view = sampleOrderView(orderId, listing(), OrderStatus.LIVE);
        when(commands.send(any())).thenReturn(objectMapper.writeValueAsString(view));

        OrderCommandController.ModifyOrderRequest request = new OrderCommandController.ModifyOrderRequest(
                1L, new BigDecimal("200"), new BigDecimal("155.00"));

        OrderView result = controller.modify(jwt, orderId, request);
        assertThat(result).isNotNull();
    }

    @Test
    void cancelOrderSuccess() throws Exception {
        Jwt jwt = jwt("trader-1", true, "DESK-A");
        UUID orderId = UUID.randomUUID();
        OrderView view = sampleOrderView(orderId, listing(), OrderStatus.CANCELLED);
        when(commands.send(any())).thenReturn(objectMapper.writeValueAsString(view));

        OrderView result = controller.cancel(jwt, orderId);
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelAllOrdersSuccess() throws Exception {
        Jwt jwt = jwt("trader-1", true, "DESK-A");
        CancelAllView view = new CancelAllView(2);
        when(commands.send(any())).thenReturn(objectMapper.writeValueAsString(view));

        CancelAllView result = controller.cancelAll(jwt);
        assertThat(result).isNotNull();
        assertThat(result.cancelled()).isEqualTo(2);
    }

    private static Jwt jwt(String subject, boolean canTrade, String desk) {
        return Jwt.withTokenValue("mock-jwt-token")
                .header("alg", "none")
                .subject(subject)
                .claim("can_trade", canTrade)
                .claim("desk", desk)
                .build();
    }

    private static ListingSnapshot listing() {
        return new ListingSnapshot(1L, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("200"), new BigDecimal("198"));
    }

    private static OrderView sampleOrderView(UUID id, ListingSnapshot listing, OrderStatus status) {
        return new OrderView(id, 1L, "trader-1", "DESK-A", listing, OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("100"), new BigDecimal("150.00"), new BigDecimal("100"), BigDecimal.ZERO,
                BigDecimal.ZERO, status, status, "DMA", "ref-1", null, id, null, null,
                Instant.now(), Instant.now());
    }
}

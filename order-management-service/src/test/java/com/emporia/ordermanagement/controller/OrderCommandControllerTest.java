package com.emporia.ordermanagement.controller;

import com.emporia.events.TradingEvents.CancelAllView;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderCommandResult;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import com.emporia.ordermanagement.disruptor.DisruptorOrderPipeline;
import com.emporia.ordermanagement.client.StaticDataClient;
import com.emporia.ordermanagement.dto.ProcessingOutcome;
import com.emporia.ordermanagement.model.OrderInputEvent;
import com.emporia.ordermanagement.repository.OrderEventRepository;
import com.emporia.ordermanagement.repository.ProcessedCommandRepository;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import com.emporia.ordermanagement.service.AsyncDbWriter;
import com.emporia.ordermanagement.service.OrderCommandHandler;
import com.emporia.ordermanagement.service.OrderInputEventRecorder;
import com.emporia.ordermanagement.service.OrderMetrics;
import com.emporia.ordermanagement.service.OrderStateCache;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.emporia.ordermanagement.service.MemoryMappedWalLogger;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCommandControllerTest {
    private final StaticDataClient staticData = mock(StaticDataClient.class);
    private final OrderCommandHandler handler = mock(OrderCommandHandler.class);
    private final OrderInputEventRecorder inputRecorder = mock(OrderInputEventRecorder.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry observations = ObservationRegistry.create();
    private com.emporia.ordermanagement.disruptor.DisruptorOrderPipeline disruptorPipeline;
    private OrderCommandController controller;

    @BeforeEach
    void setUp() {
        observations.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meters));
        disruptorPipeline = new DisruptorOrderPipeline(handler, meters, new MemoryMappedWalLogger(null, 1), null, null, "yielding", 0, 0, 0, "", "");
        disruptorPipeline.start();
        controller = new OrderCommandController(staticData, handler, disruptorPipeline, inputRecorder, objectMapper, observations);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        if (disruptorPipeline != null) {
            disruptorPipeline.stop();
        }
    }

    @Test
    void createOrderExecutesInProcess() throws Exception {
        Jwt jwt = jwt("trader-1", true, "DESK-A");
        ListingSnapshot listing = listing();
        when(staticData.get(1L, "Bearer token")).thenReturn(listing);

        UUID orderId = UUID.randomUUID();
        OrderView view = sampleOrderView(orderId, listing, OrderStatus.LIVE);
        OrderCommandResult resultRecord = new OrderCommandResult(SCHEMA_VERSION, UUID.randomUUID(), true, 201, null, objectMapper.writeValueAsString(view));
        OrderDomainEvent domainEvent = new OrderDomainEvent(SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(), orderId, "trader-1", "DESK-A", "CREATED", 1L, OrderStatus.LIVE, Instant.now(), "{}");
        ProcessingOutcome outcome = new ProcessingOutcome(resultRecord, List.of(domainEvent));

        when(handler.handle(any())).thenReturn(outcome);

        OrderCommandController.CreateOrderRequest request = new OrderCommandController.CreateOrderRequest(
                1L, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("100"), new BigDecimal("150.00"),
                "DMA", "ref-123", null, Map.of());

        OrderView result = controller.create(jwt, "Bearer token", "idem-key", request);
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(orderId);

        ArgumentCaptor<OrderCommand> captor = ArgumentCaptor.forClass(OrderCommand.class);
        verify(handler).handle(captor.capture());
        OrderCommand command = captor.getValue();
        assertThat(command.userSubject()).isEqualTo("trader-1");
        assertThat(command.deskId()).isEqualTo("DESK-A");
    }

    @Test
    void createOrderRecordsInputEventForShadowReplay() {
        Jwt jwt = jwt("trader-1", true, "DESK-A");
        ListingSnapshot listing = listing();
        when(staticData.get(1L, "Bearer token")).thenReturn(listing);

        TradingOrderRepository orders = mock(TradingOrderRepository.class);
        OrderEventRepository events = mock(OrderEventRepository.class);
        ProcessedCommandRepository processed = mock(ProcessedCommandRepository.class);
        AsyncDbWriter asyncDbWriter = mock(AsyncDbWriter.class);
        OrderMetrics metrics = new OrderMetrics(new SimpleMeterRegistry());
        OrderStateCache cache = new OrderStateCache(orders, processed, metrics, 1000, 1000);
        OrderCommandHandler realHandler = new OrderCommandHandler(orders, events, processed, objectMapper,
                observations, metrics, cache, asyncDbWriter);
        DisruptorOrderPipeline realPipeline = new DisruptorOrderPipeline(
                realHandler, new SimpleMeterRegistry(), new MemoryMappedWalLogger(null, 1),
                null, null, "yielding", 0, 0, 0, "", "");
        realPipeline.start();
        try {
            OrderInputEventRecorder realRecorder = new OrderInputEventRecorder(asyncDbWriter, objectMapper);
            OrderCommandController realController = new OrderCommandController(
                    staticData, realHandler, realPipeline, realRecorder, objectMapper, observations);
            OrderCommandController.CreateOrderRequest request = new OrderCommandController.CreateOrderRequest(
                    1L, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("100"), new BigDecimal("150.00"),
                    "DMA", "ref-123", null, Map.of());

            realController.create(jwt, "Bearer token", "rest-idem-key", request);

            verify(asyncDbWriter).enqueue(any(OrderInputEvent.class));
        } finally {
            realPipeline.stop();
        }
    }

    @Test
    void createOrderRejectsNonTrader() {
        Jwt jwt = jwt("user-1", false, "DESK-A");
        OrderCommandController.CreateOrderRequest request = new OrderCommandController.CreateOrderRequest(
                1L, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("10"), new BigDecimal("100"),
                "DMA", "ref", null, null);

        assertThatThrownBy(() -> controller.create(jwt, "Bearer token", "idem-key", request))
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

        assertThatThrownBy(() -> controller.create(jwt, "Bearer token", "idem-key", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Destination must be DMA, SMART, or VWAP");
    }

    @Test
    void modifyOrderSuccess() throws Exception {
        Jwt jwt = jwt("trader-1", true, "DESK-A");
        UUID orderId = UUID.randomUUID();
        OrderView view = sampleOrderView(orderId, listing(), OrderStatus.LIVE);
        OrderCommandResult resultRecord = new OrderCommandResult(SCHEMA_VERSION, UUID.randomUUID(), true, 200, null, objectMapper.writeValueAsString(view));
        ProcessingOutcome outcome = new ProcessingOutcome(resultRecord, List.of());
        when(handler.handle(any())).thenReturn(outcome);

        OrderCommandController.ModifyOrderRequest request = new OrderCommandController.ModifyOrderRequest(
                1L, new BigDecimal("200"), new BigDecimal("155.00"));

        OrderView result = controller.modify(jwt, orderId, "idem-key", request);
        assertThat(result).isNotNull();
    }

    @Test
    void cancelOrderSuccess() throws Exception {
        Jwt jwt = jwt("trader-1", true, "DESK-A");
        UUID orderId = UUID.randomUUID();
        OrderView view = sampleOrderView(orderId, listing(), OrderStatus.CANCELLED);
        OrderCommandResult resultRecord = new OrderCommandResult(SCHEMA_VERSION, UUID.randomUUID(), true, 200, null, objectMapper.writeValueAsString(view));
        ProcessingOutcome outcome = new ProcessingOutcome(resultRecord, List.of());
        when(handler.handle(any())).thenReturn(outcome);

        OrderView result = controller.cancel(jwt, orderId, "idem-key");
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelAllOrdersSuccess() throws Exception {
        Jwt jwt = jwt("trader-1", true, "DESK-A");
        CancelAllView view = new CancelAllView(2);
        OrderCommandResult resultRecord = new OrderCommandResult(SCHEMA_VERSION, UUID.randomUUID(), true, 200, null, objectMapper.writeValueAsString(view));
        ProcessingOutcome outcome = new ProcessingOutcome(resultRecord, List.of());
        when(handler.handle(any())).thenReturn(outcome);

        CancelAllView result = controller.cancelAll(jwt, "idem-key");
        assertThat(result).isNotNull();
        assertThat(result.cancelled()).isEqualTo(2);
    }

    @Test
    void returnsServiceUnavailableWhenKillSwitchRejectsTheCommand() {
        Jwt jwt = jwt("trader-1", true, "DESK-A");
        when(staticData.get(1L, "Bearer token")).thenReturn(listing());
        disruptorPipeline.engageKillSwitch("ops-drill");

        OrderCommandController.CreateOrderRequest request = new OrderCommandController.CreateOrderRequest(
                1L, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("100"), new BigDecimal("150.00"),
                "DMA", "ref-123", null, Map.of());

        assertThatThrownBy(() -> controller.create(jwt, "Bearer token", "idem-key", request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode().value()).isEqualTo(503));
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

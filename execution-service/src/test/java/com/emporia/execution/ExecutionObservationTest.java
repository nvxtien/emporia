package com.emporia.execution;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderDomainEvent;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Covers {@code emporia.execution.venue.operation} from REWORK_NOTE Phase 1_1.
 *
 * <p>Exchange-core performs a synchronous disk checkpoint per operation, so this
 * observation is where that cost becomes visible; the error path is asserted
 * because a venue failure is precisely when latency matters.
 */
class ExecutionObservationTest {
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final TradingDataClient tradingData = mock(TradingDataClient.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final ExecutionVenueGateway venue = mock(ExecutionVenueGateway.class);
    private final ExecutionCommandPublisher executionCommands = mock(ExecutionCommandPublisher.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry observations = ObservationRegistry.create();
    private ExecutionEventConsumer consumer;

    @BeforeEach
    void setUp() {
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        consumer = new ExecutionEventConsumer(objectMapper, kafka, tradingData, scheduler, venue,
                executionCommands, meters, observations, "orders.commands", 60, 5, "exchange-core");
    }

    @Test
    void recordsVenueSubmitWithTheConfiguredVenueMode() throws Exception {
        consumer.consume(event("CREATED", order(OrderStatus.LIVE, null)));

        // "exchange-core" is normalised to the exchange_core tag value.
        assertThat(timerCount("emporia.execution.venue.operation", "venue_mode", "exchange_core",
                "operation", "submit", "outcome", "success")).isEqualTo(1);
    }

    @Test
    void recordsVenueCancelSeparatelyFromSubmit() throws Exception {
        consumer.consume(event("CANCEL_REQUESTED", order(OrderStatus.LIVE, OrderStatus.CANCELLED)));

        assertThat(timerCount("emporia.execution.venue.operation", "operation", "cancel",
                "outcome", "success")).isEqualTo(1);
        assertThat(timerCount("emporia.execution.venue.operation", "operation", "submit",
                "outcome", "success")).isZero();
    }

    @Test
    void recordsAnErrorOutcomeWhenTheVenueRejects() throws Exception {
        doThrow(new IllegalStateException("venue unavailable")).when(venue).modify(any());

        assertThatThrownBy(() -> consumer.consume(event("MODIFIED", order(OrderStatus.LIVE, null))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(timerCount("emporia.execution.venue.operation", "operation", "modify",
                "outcome", "error")).isEqualTo(1);
    }

    private long timerCount(String name, String... tags) {
        return meters.find(name).tags(tags).timer() == null
                ? 0 : meters.find(name).tags(tags).timer().count();
    }

    private OrderDomainEvent event(String type, OrderView order) throws Exception {
        return new OrderDomainEvent(
                SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(), order.id(),
                order.ownerSubject(), order.deskId(), type, order.version(), order.status(),
                Instant.parse("2026-07-26T00:00:00Z"), objectMapper.writeValueAsString(order)
        );
    }

    private static OrderView order(OrderStatus status, OrderStatus targetStatus) {
        UUID id = UUID.randomUUID();
        return new OrderView(id, 1L, "trader-1", "DESK-A", listing(), OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("100"), new BigDecimal("150.00"), new BigDecimal("100"), BigDecimal.ZERO,
                BigDecimal.ZERO, status, targetStatus, "DMA", "ref-1", null, id, null, null,
                Instant.now(), Instant.now());
    }

    private static ListingSnapshot listing() {
        return new ListingSnapshot(1L, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("200"), new BigDecimal("198"));
    }
}

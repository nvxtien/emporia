package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.CommandType;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderCommandResult;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.ordermanagement.model.OrderEvent;
import com.emporia.ordermanagement.model.ProcessedCommand;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.OrderEventRepository;
import com.emporia.ordermanagement.repository.ProcessedCommandRepository;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@code emporia.oms.command.handle} and the quantity/price half of
 * {@code emporia.risk.check} from REWORK_NOTE Phase 1_1.
 *
 * <p>Asserts the rejection and duplicate paths, not just the happy path — those
 * are where latency problems hide and are the easiest to leave unrecorded.
 */
class OrderCommandHandlerObservationTest {
    private static final String USER = "trader-one";

    private final TradingOrderRepository orders = mock(TradingOrderRepository.class);
    private final OrderEventRepository events = mock(OrderEventRepository.class);
    private final ProcessedCommandRepository processed = mock(ProcessedCommandRepository.class);
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry observations = ObservationRegistry.create();
    private OrderCommandHandler handler;

    @BeforeEach
    void setUp() {
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        OrderMetrics metrics = new OrderMetrics(meters);
        OrderStateCache cache = new OrderStateCache(orders, processed, metrics, null, 1000, 1000);
        AsyncDbWriter asyncDbWriter = mock(AsyncDbWriter.class);
        handler = new OrderCommandHandler(orders, events, processed, new ObjectMapper(), observations, metrics, cache, asyncDbWriter);
        when(processed.findById(any())).thenReturn(Optional.empty());
        when(orders.existsById(any())).thenReturn(false);
        when(events.save(any(OrderEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // The real repository assigns the optimistic-locking version on save.
        when(orders.save(any(TradingOrder.class))).thenAnswer(invocation -> {
            TradingOrder order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "version",
                    order.getVersion() == null ? 1L : order.getVersion() + 1);
            return order;
        });
    }

    @Test
    void recordsSuccessfulHandleAndAllowedRiskCheck() {
        handler.handle(createCommand(new BigDecimal("10"), new BigDecimal("100")));

        assertThat(timerCount("emporia.oms.command.handle", "command_type", "create", "outcome", "success"))
                .isEqualTo(1);
        assertThat(timerCount("emporia.risk.check", "decision", "allow", "reason", "ok")).isEqualTo(1);
    }

    @Test
    void recordsQuantityDenialWhenTheQuantityIsMisaligned() {
        // Size increment is 1, so a fractional quantity must be denied.
        handler.handle(createCommand(new BigDecimal("10.5"), new BigDecimal("100")));

        assertThat(timerCount("emporia.risk.check", "decision", "deny", "reason", "quantity")).isEqualTo(1);
        assertThat(timerCount("emporia.oms.command.handle", "command_type", "create", "outcome", "rejected"))
                .isEqualTo(1);
    }

    @Test
    void recordsSymbolDenialWhenTheLimitPriceIsOffTick() {
        // Tick size is 0.01, so a sub-tick price must be denied.
        handler.handle(createCommand(new BigDecimal("10"), new BigDecimal("100.005")));

        assertThat(timerCount("emporia.risk.check", "decision", "deny", "reason", "symbol")).isEqualTo(1);
        assertThat(timerCount("emporia.oms.command.handle", "command_type", "create", "outcome", "rejected"))
                .isEqualTo(1);
    }

    @Test
    void recordsDuplicateOutcomeForARedeliveredCommand() {
        OrderCommand command = createCommand(new BigDecimal("10"), new BigDecimal("100"));
        OrderCommandResult cached = new OrderCommandResult(SCHEMA_VERSION, command.commandId(),
                true, 201, "already processed", null);
        when(processed.findById(command.commandId())).thenReturn(Optional.of(new ProcessedCommand(cached)));
        when(events.findByCommandIdOrderByOccurredAtAsc(command.commandId())).thenReturn(List.of());

        handler.handle(command);

        assertThat(timerCount("emporia.oms.command.handle", "command_type", "create", "outcome", "duplicate"))
                .isEqualTo(1);
        // A redelivery must not re-run the risk gate.
        assertThat(meters.find("emporia.risk.check").timer()).isNull();
    }

    private long timerCount(String name, String... tags) {
        return meters.find(name).tags(tags).timer() == null
                ? 0 : meters.find(name).tags(tags).timer().count();
    }

    private static OrderCommand createCommand(BigDecimal quantity, BigDecimal limitPrice) {
        return new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.CREATE,
                USER, Instant.EPOCH, UUID.randomUUID(), null, listing(),
                OrderSide.BUY, OrderType.LIMIT,
                quantity, limitPrice, "DMA", "ref", null, Map.of()
        );
    }

    private static ListingSnapshot listing() {
        return new ListingSnapshot(1L, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("200"), new BigDecimal("198"));
    }
}

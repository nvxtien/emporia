package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.CommandType;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderCommandResult;
import com.emporia.events.time.DomainClock;
import com.emporia.ordermanagement.model.OrderEvent;
import com.emporia.ordermanagement.model.OrderInputEvent;
import com.emporia.ordermanagement.model.ProcessedCommand;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.OrderEventRepository;
import com.emporia.ordermanagement.repository.OrderInputEventRepository;
import com.emporia.ordermanagement.repository.ProcessedCommandRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderShadowComparisonServiceTest {
    private final OrderInputEventRepository inputEvents = mock(OrderInputEventRepository.class);
    private final ProcessedCommandRepository processed = mock(ProcessedCommandRepository.class);
    private final OrderEventRepository events = mock(OrderEventRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void emptyInputLogDoesNotReportPerfectCoverage() {
        OrderShadowComparisonService service = new OrderShadowComparisonService(inputEvents, processed, events, objectMapper);
        when(inputEvents.findByOrderBySequenceIdDesc(any(Pageable.class))).thenReturn(List.of());

        OrderShadowComparisonService.ShadowComparisonReport report = service.compare(10);

        assertThat(report.totalCommands()).isZero();
        assertThat(report.passRate()).isZero();
    }

    @Test
    void reportsPerfectMatchForRejectedCommandWithNoEvents() throws Exception {
        OrderShadowComparisonService service = new OrderShadowComparisonService(inputEvents, processed, events, objectMapper);
        OrderCommand command = new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.CREATE,
                "trader", Instant.EPOCH, null, null, listing(),
                com.emporia.events.TradingEvents.OrderSide.BUY, com.emporia.events.TradingEvents.OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"), "DMA", "ref", null, Map.of()
        );
        OrderInputEvent input = new OrderInputEvent(command, objectMapper.writeValueAsString(command));
        when(inputEvents.findByOrderBySequenceIdDesc(any(Pageable.class))).thenReturn(List.of(input));
        when(processed.findById(command.commandId())).thenReturn(Optional.of(new ProcessedCommand(
                new OrderCommandResult(SCHEMA_VERSION, command.commandId(), false, 400, "Create command is incomplete", null))));
        when(events.findByCommandIdOrderByOccurredAtAsc(command.commandId())).thenReturn(List.of());

        OrderShadowComparisonService.ShadowComparisonReport report = service.compare(10);

        assertThat(report.totalCommands()).isEqualTo(1);
        assertThat(report.matchedCommands()).isEqualTo(1);
        assertThat(report.mismatchedCommands()).isZero();
        assertThat(report.passRate()).isEqualTo(1.0d);
    }

    @Test
    void reportsMismatchWhenPersistedResultDiffersFromReplay() throws Exception {
        OrderShadowComparisonService service = new OrderShadowComparisonService(inputEvents, processed, events, objectMapper);
        OrderCommand command = new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.CREATE,
                "trader", Instant.EPOCH, null, null, listing(),
                com.emporia.events.TradingEvents.OrderSide.BUY, com.emporia.events.TradingEvents.OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"), "DMA", "ref", null, Map.of()
        );
        OrderInputEvent input = new OrderInputEvent(command, objectMapper.writeValueAsString(command));
        when(inputEvents.findByOrderBySequenceIdDesc(any(Pageable.class))).thenReturn(List.of(input));
        when(processed.findById(command.commandId())).thenReturn(Optional.of(new ProcessedCommand(
                new OrderCommandResult(SCHEMA_VERSION, command.commandId(), false, 409, "wrong", null))));
        when(events.findByCommandIdOrderByOccurredAtAsc(command.commandId())).thenReturn(List.of());

        OrderShadowComparisonService.ShadowComparisonReport report = service.compare(10);

        assertThat(report.totalCommands()).isEqualTo(1);
        assertThat(report.matchedCommands()).isZero();
        assertThat(report.mismatchedCommands()).isEqualTo(1);
        assertThat(report.mismatches()).hasSize(1);
    }

    @Test
    void replaysDuplicateCreatesUsingSandboxSideEffects() throws Exception {
        OrderShadowComparisonService service = new OrderShadowComparisonService(inputEvents, processed, events, objectMapper);
        OrderCommand command = new OrderCommand(
                SCHEMA_VERSION, UUID.randomUUID(), CommandType.CREATE,
                "trader", "DESK-A", Instant.parse("2026-08-10T00:00:00Z"), UUID.randomUUID(), null, listing(),
                com.emporia.events.TradingEvents.OrderSide.BUY, com.emporia.events.TradingEvents.OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"), "DMA", "ref", null, Map.of()
        );
        OrderInputEvent first = sequenced(new OrderInputEvent(command, objectMapper.writeValueAsString(command)), 10L);
        OrderInputEvent duplicate = sequenced(new OrderInputEvent(command, objectMapper.writeValueAsString(command)), 11L);
        when(inputEvents.findBySequenceIdGreaterThanOrderBySequenceIdAsc(eq(9L), any(Pageable.class)))
                .thenReturn(List.of(first, duplicate));

        Instant actualTime = Instant.parse("2026-08-10T01:00:00Z");
        try {
            DomainClock.use(Clock.fixed(actualTime, ZoneOffset.UTC));
            TradingOrder order = new TradingOrder(command.orderId(), command.userSubject(), command.deskId(),
                    command.listing(), command.side(), command.orderType(), command.quantity(), command.limitPrice(),
                    command.destination(), command.originatorReference(), null, null, "{}");
            String payload = objectMapper.writeValueAsString(order.view());
            OrderEvent created = new OrderEvent(command.commandId(), order, "CREATED",
                    "Order accepted by Emporia", payload);
            OrderCommandResult result = new OrderCommandResult(SCHEMA_VERSION, command.commandId(),
                    true, 201, null, payload);
            when(processed.findById(command.commandId())).thenReturn(Optional.of(new ProcessedCommand(result)));
            when(events.findByCommandIdOrderByOccurredAtAsc(command.commandId())).thenReturn(List.of(created));

            DomainClock.use(Clock.fixed(actualTime.plusSeconds(60), ZoneOffset.UTC));
            OrderShadowComparisonService.ShadowComparisonReport report = service.compare(2, 9L);

            assertThat(report.totalCommands()).isEqualTo(2);
            assertThat(report.mismatches()).isEmpty();
            assertThat(report.matchedCommands()).isEqualTo(2);
            assertThat(report.mismatchedCommands()).isZero();
            assertThat(report.passRate()).isEqualTo(1.0d);
        } finally {
            DomainClock.reset();
        }
    }

    private static ListingSnapshot listing() {
        return new ListingSnapshot(1L, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("200"), new BigDecimal("198"));
    }

    private static OrderInputEvent sequenced(OrderInputEvent inputEvent, long sequenceId) {
        try {
            Field field = OrderInputEvent.class.getDeclaredField("sequenceId");
            field.setAccessible(true);
            field.set(inputEvent, sequenceId);
            return inputEvent;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not set input-event sequence id", exception);
        }
    }
}

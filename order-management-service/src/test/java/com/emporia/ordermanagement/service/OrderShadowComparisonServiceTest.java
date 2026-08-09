package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.CommandType;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderCommandResult;
import com.emporia.ordermanagement.model.OrderInputEvent;
import com.emporia.ordermanagement.model.ProcessedCommand;
import com.emporia.ordermanagement.repository.OrderEventRepository;
import com.emporia.ordermanagement.repository.OrderInputEventRepository;
import com.emporia.ordermanagement.repository.ProcessedCommandRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
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
        when(inputEvents.findAllByOrderBySequenceIdAsc()).thenReturn(List.of());

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
        when(inputEvents.findAllByOrderBySequenceIdAsc()).thenReturn(List.of(input));
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
        when(inputEvents.findAllByOrderBySequenceIdAsc()).thenReturn(List.of(input));
        when(processed.findById(command.commandId())).thenReturn(Optional.of(new ProcessedCommand(
                new OrderCommandResult(SCHEMA_VERSION, command.commandId(), false, 409, "wrong", null))));
        when(events.findByCommandIdOrderByOccurredAtAsc(command.commandId())).thenReturn(List.of());

        OrderShadowComparisonService.ShadowComparisonReport report = service.compare(10);

        assertThat(report.totalCommands()).isEqualTo(1);
        assertThat(report.matchedCommands()).isZero();
        assertThat(report.mismatchedCommands()).isEqualTo(1);
        assertThat(report.mismatches()).hasSize(1);
    }

    private static ListingSnapshot listing() {
        return new ListingSnapshot(1L, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("200"), new BigDecimal("198"));
    }
}

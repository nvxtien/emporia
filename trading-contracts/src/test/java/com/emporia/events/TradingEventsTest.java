package com.emporia.events;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TradingEventsTest {

    @Test
    void verifiesEnumValuesAndValueOf() {
        assertThat(TradingEvents.CommandType.values()).hasSize(4);
        assertThat(TradingEvents.CommandType.valueOf("CREATE")).isEqualTo(TradingEvents.CommandType.CREATE);

        assertThat(TradingEvents.OrderSide.values()).hasSize(2);
        assertThat(TradingEvents.OrderSide.valueOf("BUY")).isEqualTo(TradingEvents.OrderSide.BUY);

        assertThat(TradingEvents.OrderType.values()).hasSize(2);
        assertThat(TradingEvents.OrderType.valueOf("LIMIT")).isEqualTo(TradingEvents.OrderType.LIMIT);

        assertThat(TradingEvents.OrderStatus.values()).hasSize(5);
        assertThat(TradingEvents.OrderStatus.valueOf("LIVE")).isEqualTo(TradingEvents.OrderStatus.LIVE);

        assertThat(TradingEvents.ExecutionCommandType.values()).hasSize(3);
        assertThat(TradingEvents.ExecutionCommandType.valueOf("FILL")).isEqualTo(TradingEvents.ExecutionCommandType.FILL);
    }

    @Test
    void verifiesListingSnapshotRecord() {
        TradingEvents.ListingSnapshot snapshot = new TradingEvents.ListingSnapshot(
                1L, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("200"), new BigDecimal("198")
        );

        assertThat(snapshot.id()).isEqualTo(1L);
        assertThat(snapshot.version()).isEqualTo(1);
        assertThat(snapshot.symbol()).isEqualTo("AAPL");
        assertThat(snapshot.name()).isEqualTo("Apple Inc.");
        assertThat(snapshot.marketSymbol()).isEqualTo("AAPL");
        assertThat(snapshot.exchangeMic()).isEqualTo("XNAS");
        assertThat(snapshot.exchangeName()).isEqualTo("Nasdaq");
        assertThat(snapshot.countryCode()).isEqualTo("US");
        assertThat(snapshot.currency()).isEqualTo("USD");
        assertThat(snapshot.tickSize()).isEqualByComparingTo("0.01");
        assertThat(snapshot.sizeIncrement()).isEqualByComparingTo("1");
        assertThat(snapshot.referencePrice()).isEqualByComparingTo("200");
        assertThat(snapshot.previousClose()).isEqualByComparingTo("198");
    }

    @Test
    void verifiesOrderCommandConstructors() {
        UUID commandId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Instant now = Instant.now();

        TradingEvents.OrderCommand command1 = new TradingEvents.OrderCommand(
                TradingEvents.SCHEMA_VERSION, commandId, TradingEvents.CommandType.CREATE,
                "user1", "desk1", now, orderId, null, null,
                TradingEvents.OrderSide.BUY, TradingEvents.OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"), "DMA", "ref1", null, Map.of()
        );

        assertThat(command1.schemaVersion()).isEqualTo(TradingEvents.SCHEMA_VERSION);
        assertThat(command1.commandId()).isEqualTo(commandId);
        assertThat(command1.commandType()).isEqualTo(TradingEvents.CommandType.CREATE);
        assertThat(command1.userSubject()).isEqualTo("user1");
        assertThat(command1.deskId()).isEqualTo("desk1");
        assertThat(command1.requestedAt()).isEqualTo(now);
        assertThat(command1.orderId()).isEqualTo(orderId);

        // 16-parameter constructor defaults deskId to userSubject
        TradingEvents.OrderCommand command2 = new TradingEvents.OrderCommand(
                TradingEvents.SCHEMA_VERSION, commandId, TradingEvents.CommandType.CREATE,
                "user2", now, orderId, null, null,
                TradingEvents.OrderSide.BUY, TradingEvents.OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"), "DMA", "ref2", null, Map.of()
        );

        assertThat(command2.userSubject()).isEqualTo("user2");
        assertThat(command2.deskId()).isEqualTo("user2");
    }

    @Test
    void verifiesOrderCommandResultRecord() {
        UUID commandId = UUID.randomUUID();
        TradingEvents.OrderCommandResult result = new TradingEvents.OrderCommandResult(
                TradingEvents.SCHEMA_VERSION, commandId, true, 200, "OK", "{}"
        );

        assertThat(result.schemaVersion()).isEqualTo(TradingEvents.SCHEMA_VERSION);
        assertThat(result.commandId()).isEqualTo(commandId);
        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.detail()).isEqualTo("OK");
        assertThat(result.payload()).isEqualTo("{}");
    }

    @Test
    void verifiesOrderDomainEventRecord() {
        UUID eventId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Instant now = Instant.now();

        TradingEvents.OrderDomainEvent event = new TradingEvents.OrderDomainEvent(
                TradingEvents.SCHEMA_VERSION, eventId, commandId, orderId,
                "user1", "desk1", "CREATED", 1L, TradingEvents.OrderStatus.LIVE, now, "{}"
        );

        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.commandId()).isEqualTo(commandId);
        assertThat(event.orderId()).isEqualTo(orderId);
        assertThat(event.userSubject()).isEqualTo("user1");
        assertThat(event.deskId()).isEqualTo("desk1");
        assertThat(event.eventType()).isEqualTo("CREATED");
        assertThat(event.orderVersion()).isEqualTo(1L);
        assertThat(event.status()).isEqualTo(TradingEvents.OrderStatus.LIVE);
        assertThat(event.occurredAt()).isEqualTo(now);
        assertThat(event.payload()).isEqualTo("{}");
    }

    @Test
    void verifiesExecutionCommandRecord() {
        UUID commandId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Instant now = Instant.now();

        TradingEvents.ExecutionCommand command = new TradingEvents.ExecutionCommand(
                TradingEvents.SCHEMA_VERSION, commandId, TradingEvents.ExecutionCommandType.FILL,
                orderId, "desk1", "ref1", new BigDecimal("5"), new BigDecimal("100"), "XNAS", now, "detail"
        );

        assertThat(command.schemaVersion()).isEqualTo(TradingEvents.SCHEMA_VERSION);
        assertThat(command.commandId()).isEqualTo(commandId);
        assertThat(command.commandType()).isEqualTo(TradingEvents.ExecutionCommandType.FILL);
        assertThat(command.orderId()).isEqualTo(orderId);
        assertThat(command.deskId()).isEqualTo("desk1");
        assertThat(command.executionReference()).isEqualTo("ref1");
        assertThat(command.quantity()).isEqualByComparingTo("5");
        assertThat(command.price()).isEqualByComparingTo("100");
        assertThat(command.venue()).isEqualTo("XNAS");
        assertThat(command.occurredAt()).isEqualTo(now);
        assertThat(command.detail()).isEqualTo("detail");
    }

    @Test
    void verifiesOrderViewAndCancelAllViewRecords() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        TradingEvents.OrderView view = new TradingEvents.OrderView(
                id, 1L, "user1", "desk1", null, TradingEvents.OrderSide.BUY, TradingEvents.OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("10"), BigDecimal.ZERO, null,
                TradingEvents.OrderStatus.LIVE, TradingEvents.OrderStatus.LIVE, "DMA", "ref1",
                null, id, "{}", null, now, now
        );

        assertThat(view.id()).isEqualTo(id);
        assertThat(view.version()).isEqualTo(1L);
        assertThat(view.ownerSubject()).isEqualTo("user1");
        assertThat(view.deskId()).isEqualTo("desk1");
        assertThat(view.destination()).isEqualTo("DMA");

        TradingEvents.CancelAllView cancelAll = new TradingEvents.CancelAllView(5);
        assertThat(cancelAll.cancelled()).isEqualTo(5);
    }

    @Test
    void verifiesStrategyStateViewAndExecutionRecoveryViewRecords() {
        TradingEvents.StrategyStateView strategyStateNullChildren = new TradingEvents.StrategyStateView(null, null);
        assertThat(strategyStateNullChildren.parent()).isNull();
        assertThat(strategyStateNullChildren.children()).isEmpty();

        TradingEvents.StrategyStateView strategyState = new TradingEvents.StrategyStateView(null, List.of());
        assertThat(strategyState.children()).isEmpty();

        TradingEvents.ExecutionRecoveryView recoveryNullLists = new TradingEvents.ExecutionRecoveryView(null, null);
        assertThat(recoveryNullLists.directOrders()).isEmpty();
        assertThat(recoveryNullLists.strategies()).isEmpty();

        TradingEvents.ExecutionRecoveryView recovery = new TradingEvents.ExecutionRecoveryView(List.of(), List.of());
        assertThat(recovery.directOrders()).isEmpty();
        assertThat(recovery.strategies()).isEmpty();
    }
}

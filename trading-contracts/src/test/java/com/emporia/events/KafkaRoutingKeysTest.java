package com.emporia.events;

import com.emporia.events.TradingEvents.ExecutionCommand;
import com.emporia.events.TradingEvents.ExecutionCommandType;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThat;

class KafkaRoutingKeysTest {
    @Test
    void orderCommandUsesOrderIdWhenPresent() {
        UUID orderId = UUID.randomUUID();
        OrderCommand command = orderCommand(orderId, "trader-1");

        assertThat(KafkaRoutingKeys.orderCommand(command)).isEqualTo(orderId.toString());
    }

    @Test
    void orderCommandFallsBackToSubjectWhenNoOrderId() {
        OrderCommand command = new OrderCommand(SCHEMA_VERSION, UUID.randomUUID(),
                TradingEvents.CommandType.CANCEL_ALL, "trader-1", "DESK-A", Instant.now(),
                null, null, null, null, null, null, null, "DMA", "ref", null, Map.of());

        assertThat(KafkaRoutingKeys.orderCommand(command)).isEqualTo("trader-1");
    }

    @Test
    void orderEventUsesOrderId() {
        UUID orderId = UUID.randomUUID();
        OrderDomainEvent event = new OrderDomainEvent(SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(),
                orderId, "trader-1", "DESK-A", "CREATED", 1L, OrderStatus.LIVE, Instant.now(), "{}");

        assertThat(KafkaRoutingKeys.orderEvent(event)).isEqualTo(orderId.toString());
    }

    @Test
    void orderResultUsesCommandId() {
        UUID commandId = UUID.randomUUID();
        OrderCommand command = orderCommand(UUID.randomUUID(), "trader-1");
        OrderCommand commandWithId = new OrderCommand(SCHEMA_VERSION, commandId, TradingEvents.CommandType.CREATE,
            command.userSubject(), command.deskId(), command.requestedAt(), command.orderId(), command.expectedVersion(),
                command.listing(), command.side(), command.orderType(), command.quantity(), command.limitPrice(),
                command.destination(), command.originatorReference(), command.parentOrderId(), command.executionParameters());

        assertThat(KafkaRoutingKeys.orderResult(commandWithId)).isEqualTo(commandId.toString());
    }

    @Test
    void executionCommandUsesOrderId() {
        UUID orderId = UUID.randomUUID();
        ExecutionCommand command = new ExecutionCommand(SCHEMA_VERSION, UUID.randomUUID(),
                ExecutionCommandType.FILL, orderId, "desk-a", "ref", new BigDecimal("1"), new BigDecimal("10"),
                "XNAS", Instant.now(), null);

        assertThat(KafkaRoutingKeys.executionCommand(command)).isEqualTo(orderId.toString());
    }

    @Test
    void strategyChildCreateUsesParentOrderId() {
        UUID orderId = UUID.randomUUID();
        assertThat(KafkaRoutingKeys.strategyChildCreate(orderId)).isEqualTo(orderId.toString());
    }

    private static OrderCommand orderCommand(UUID orderId, String subject) {
        ListingSnapshot listing = new ListingSnapshot(1, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq",
                "US", "USD", new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("200"), new BigDecimal("198"));
        return new OrderCommand(SCHEMA_VERSION, UUID.randomUUID(), TradingEvents.CommandType.CREATE,
                subject, "DESK-A", Instant.now(), orderId, null, listing, OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"), "DMA", "ref", null, Map.of());
    }
}
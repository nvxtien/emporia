package com.emporia.events;

import com.emporia.events.TradingEvents.ExecutionCommand;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderDomainEvent;

import java.util.UUID;

/**
 * Central routing-key policy for Kafka producers.
 *
 * <p>Order-scoped messages use the order id, command replies use the command
 * id, and strategy child commands use the parent order id so partition affinity
 * stays deterministic.
 */
public final class KafkaRoutingKeys {
    private KafkaRoutingKeys() {
    }

    public static String orderCommand(OrderCommand command) {
        return command.orderId() == null ? command.userSubject() : command.orderId().toString();
    }

    public static String orderEvent(OrderDomainEvent event) {
        return event.orderId().toString();
    }

    public static String orderResult(OrderCommand command) {
        return command.commandId().toString();
    }

    public static String executionCommand(ExecutionCommand command) {
        return command.orderId().toString();
    }

    public static String strategyChildCreate(UUID parentOrderId) {
        return parentOrderId.toString();
    }
}
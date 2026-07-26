package com.emporia.ordermanagement.dto;

import com.emporia.events.TradingEvents;
import com.emporia.ordermanagement.model.OrderEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderEventView(UUID id, long orderVersion, String eventType, TradingEvents.OrderStatus status,
                      BigDecimal quantity,
                      BigDecimal price, String message, Instant occurredAt) {
    public static OrderEventView from(OrderEvent event) {
        return new OrderEventView(event.getId(), event.getOrderVersion(), event.getEventType(), event.getStatus(),
                event.getQuantity(), event.getPrice(), event.getMessage(), event.getOccurredAt());
    }
}
package com.emporia.ordermanagement.dto;

import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.ordermanagement.model.TradingOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ExecutionStrategyView(
        UUID orderId,
        UUID rootOrderId,
        String deskId,
        String ownerSubject,
        String symbol,
        OrderSide side,
        String destination,
        OrderStatus status,
        OrderStatus targetStatus,
        BigDecimal quantity,
        BigDecimal tradedQuantity,
        BigDecimal remainingQuantity,
        BigDecimal averageTradePrice,
        long childOrderCount,
        Instant createdAt,
        Instant updatedAt) {

    public static ExecutionStrategyView from(final TradingOrder order, final long childOrderCount) {
        return new ExecutionStrategyView(
                order.getId(),
                order.getRootOrderId(),
                order.getDeskId(),
                order.getUserSubject(),
                order.getListing().getSymbol(),
                order.getSide(),
                order.getDestination(),
                order.getStatus(),
                order.getTargetStatus(),
                order.getQuantity(),
                order.getTradedQuantity(),
                order.getRemainingQuantity(),
                order.getAverageTradePrice(),
                childOrderCount,
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}

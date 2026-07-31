package com.emporia.ordermanagement.dto;

import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.ordermanagement.model.Execution;
import com.emporia.ordermanagement.model.TradingOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AdminExecutionView(
        UUID id,
        String executionReference,
        UUID orderId,
        UUID rootOrderId,
        UUID parentOrderId,
        String deskId,
        String ownerSubject,
        String symbol,
        OrderSide side,
        String destination,
        OrderStatus orderStatus,
        BigDecimal quantity,
        BigDecimal price,
        String venue,
        Instant executedAt) {

    public static AdminExecutionView from(final Execution execution) {
        final TradingOrder order = execution.getOrder();
        return new AdminExecutionView(
                execution.getId(),
                execution.getExecutionReference(),
                order.getId(),
                order.getRootOrderId(),
                order.getParentOrderId(),
                order.getDeskId(),
                order.getUserSubject(),
                order.getListing().getSymbol(),
                order.getSide(),
                order.getDestination(),
                order.getStatus(),
                execution.getQuantity(),
                execution.getPrice(),
                execution.getVenue(),
                execution.getExecutedAt());
    }
}

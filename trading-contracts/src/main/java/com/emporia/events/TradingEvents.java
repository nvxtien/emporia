package com.emporia.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TradingEvents {

    public static final int SCHEMA_VERSION = 1;
    public static final String ORDER_COMMANDS_TOPIC = "emporia.order.commands.v1";
    public static final String ORDER_RESULTS_TOPIC = "emporia.order.results.v1";
    public static final String ORDERS_TOPIC = "emporia.orders.v1";
    public static final String EXECUTION_COMMANDS_TOPIC = "emporia.execution.commands.v1";

    private TradingEvents() {
    }

    public enum CommandType { CREATE, MODIFY, CANCEL, CANCEL_ALL }

    public enum OrderSide { BUY, SELL }

    public enum OrderType { MARKET, LIMIT }

    public enum OrderStatus { LIVE, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED }

    public enum ExecutionCommandType { FILL, REJECT, CANCEL }

    public record ListingSnapshot(
            long id,
            int version,
            String symbol,
            String name,
            String marketSymbol,
            String exchangeMic,
            String exchangeName,
            String countryCode,
            String currency,
            BigDecimal tickSize,
            BigDecimal sizeIncrement,
            BigDecimal referencePrice,
            BigDecimal previousClose,
            long tickSizeScaled,
            long sizeIncrementScaled
    ) {
        public ListingSnapshot(long id, int version, String symbol, String name, String marketSymbol,
                               String exchangeMic, String exchangeName, String countryCode, String currency,
                               BigDecimal tickSize, BigDecimal sizeIncrement, BigDecimal referencePrice,
                               BigDecimal previousClose) {
            this(id, version, symbol, name, marketSymbol, exchangeMic, exchangeName, countryCode, currency,
                    tickSize, sizeIncrement, referencePrice, previousClose,
                    com.emporia.events.math.FixedPointMath.toScaledLong(tickSize),
                    com.emporia.events.math.FixedPointMath.toScaledLong(sizeIncrement));
        }
    }

    public record OrderCommand(
            int schemaVersion,
            UUID commandId,
            CommandType commandType,
            String userSubject,
            String deskId,
            Instant requestedAt,
            UUID orderId,
            Long expectedVersion,
            ListingSnapshot listing,
            OrderSide side,
            OrderType orderType,
            BigDecimal quantity,
            BigDecimal limitPrice,
            String destination,
            String originatorReference,
            UUID parentOrderId,
            Map<String, Object> executionParameters,
            long quantityScaled,
            long limitPriceScaled
    ) {
        public OrderCommand(int schemaVersion, UUID commandId, CommandType commandType, String userSubject,
                            String deskId, Instant requestedAt, UUID orderId, Long expectedVersion, ListingSnapshot listing,
                            OrderSide side, OrderType orderType, BigDecimal quantity, BigDecimal limitPrice,
                            String destination, String originatorReference, UUID parentOrderId,
                            Map<String, Object> executionParameters) {
            this(schemaVersion, commandId, commandType, userSubject, deskId, requestedAt, orderId,
                    expectedVersion, listing, side, orderType, quantity, limitPrice, destination,
                    originatorReference, parentOrderId, executionParameters,
                    com.emporia.events.math.FixedPointMath.toScaledLong(quantity),
                    limitPrice == null ? 0L : com.emporia.events.math.FixedPointMath.toScaledLong(limitPrice));
        }

        public OrderCommand(int schemaVersion, UUID commandId, CommandType commandType, String userSubject,
                            Instant requestedAt, UUID orderId, Long expectedVersion, ListingSnapshot listing,
                            OrderSide side, OrderType orderType, BigDecimal quantity, BigDecimal limitPrice,
                            String destination, String originatorReference, UUID parentOrderId,
                            Map<String, Object> executionParameters) {
            this(schemaVersion, commandId, commandType, userSubject, userSubject, requestedAt, orderId,
                    expectedVersion, listing, side, orderType, quantity, limitPrice, destination,
                    originatorReference, parentOrderId, executionParameters);
        }

        public com.emporia.events.math.LongPair commandIdPair() {
            return com.emporia.events.math.LongPair.fromUuid(commandId());
        }

        public com.emporia.events.math.LongPair orderIdPair() {
            return com.emporia.events.math.LongPair.fromUuid(orderId());
        }

        public com.emporia.events.math.LongPair parentOrderIdPair() {
            return com.emporia.events.math.LongPair.fromUuid(parentOrderId());
        }
    }

    public record OrderCommandResult(
            int schemaVersion,
            UUID commandId,
            boolean success,
            int status,
            String detail,
            String payload
    ) {
    }

    public record OrderDomainEvent(
            int schemaVersion,
            UUID eventId,
            UUID commandId,
            UUID orderId,
            String userSubject,
            String deskId,
            String eventType,
            long orderVersion,
            OrderStatus status,
            Instant occurredAt,
            String payload
    ) {
    }

    public record ExecutionCommand(
            int schemaVersion,
            UUID commandId,
            ExecutionCommandType commandType,
            UUID orderId,
            String deskId,
            String executionReference,
            BigDecimal quantity,
            BigDecimal price,
            String venue,
            Instant occurredAt,
            String detail,
            long quantityScaled,
            long priceScaled
    ) {
        public ExecutionCommand(int schemaVersion, UUID commandId, ExecutionCommandType commandType,
                                UUID orderId, String deskId, String executionReference, BigDecimal quantity,
                                BigDecimal price, String venue, Instant occurredAt, String detail) {
            this(schemaVersion, commandId, commandType, orderId, deskId, executionReference, quantity, price,
                    venue, occurredAt, detail,
                    com.emporia.events.math.FixedPointMath.toScaledLong(quantity),
                    price == null ? 0L : com.emporia.events.math.FixedPointMath.toScaledLong(price));
        }
    }

    public record OrderView(
            UUID id,
            long version,
            String ownerSubject,
            String deskId,
            ListingSnapshot listing,
            OrderSide side,
            OrderType type,
            BigDecimal quantity,
            BigDecimal limitPrice,
            BigDecimal remainingQuantity,
            BigDecimal tradedQuantity,
            BigDecimal averageTradePrice,
            OrderStatus status,
            OrderStatus targetStatus,
            String destination,
            String originatorReference,
            UUID parentOrderId,
            UUID rootOrderId,
            String executionParameters,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record CancelAllView(int cancelled) {
    }

    /**
     * Durable execution-strategy state reconstructed from the order-management
     * projection. Execution-service uses this instead of relying on in-memory
     * timers or a private copy of child-order state.
     */
    public record StrategyStateView(OrderView parent, List<OrderView> children) {
        public StrategyStateView {
            children = children == null ? List.of() : List.copyOf(children);
        }
    }

    /**
     * Orders that execution-service must reattach to after a process restart.
     * DMA orders restore venue correlation; strategy parents restore their
     * schedules from the parent and child orders persisted in PostgreSQL.
     */
    public record ExecutionRecoveryView(
            List<OrderView> directOrders,
            List<StrategyStateView> strategies
    ) {
        public ExecutionRecoveryView {
            directOrders = directOrders == null ? List.of() : List.copyOf(directOrders);
            strategies = strategies == null ? List.of() : List.copyOf(strategies);
        }
    }
}

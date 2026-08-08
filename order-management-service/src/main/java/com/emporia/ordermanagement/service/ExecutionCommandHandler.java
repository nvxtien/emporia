package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.ExecutionCommand;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.KafkaRoutingKeys;
import com.emporia.events.sbe.SbeEncoderDecoder;
import com.emporia.ordermanagement.model.Execution;
import com.emporia.ordermanagement.model.OrderEvent;
import com.emporia.ordermanagement.model.OrderOutboxRecord;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.ExecutionRepository;
import com.emporia.ordermanagement.repository.OrderEventRepository;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;

@Service
class ExecutionCommandHandler {
    private static final List<OrderStatus> ACTIVE =
            List.of(OrderStatus.LIVE, OrderStatus.PARTIALLY_FILLED);

    private final TradingOrderRepository orders;
    private final ExecutionRepository executions;
    private final OrderEventRepository events;
    private final ObjectMapper objectMapper;
    private final OrderMetrics metrics;
    private final OrderStateCache cache;
    private final AsyncDbWriter asyncDbWriter;
    private final String ordersTopic;

    ExecutionCommandHandler(TradingOrderRepository orders, ExecutionRepository executions,
                            OrderEventRepository events, ObjectMapper objectMapper, OrderMetrics metrics,
                            OrderStateCache cache, AsyncDbWriter asyncDbWriter,
                            @Value("${emporia.kafka.orders-topic:emporia.orders.v1}") String ordersTopic) {
        this.orders = orders;
        this.executions = executions;
        this.events = events;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.cache = cache;
        this.asyncDbWriter = asyncDbWriter;
        this.ordersTopic = ordersTopic;
    }

    /**
     * Applies a venue update and every resulting parent transition.
     * Direct Java invocation without Spring AOP / CGLIB proxy reflection.
     */
    List<OrderDomainEvent> handle(ExecutionCommand command) {
        if (command.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported execution command schema version");
        }
        if (command.commandType() == com.emporia.events.TradingEvents.ExecutionCommandType.FILL
                && executions.existsByExecutionReference(command.executionReference())) {
            return List.of();
        }

        // Cache-backed lookup: the child order is almost certainly warm.
        TradingOrder order = cache.findByIdAndDeskId(command.orderId(), command.deskId())
                .orElseThrow(() -> new IllegalArgumentException("Execution order was not found on its desk"));
        List<OrderDomainEvent> result = new ArrayList<>();

        switch (command.commandType()) {
            case FILL -> applyFill(order, command, result);
            case REJECT -> reject(order, command, result);
            case CANCEL -> confirmCancel(order, command, result);
            default -> throw new IllegalArgumentException("Unsupported execution command");
        }
        List<OrderDomainEvent> events = List.copyOf(result);
        enqueueOutbox(events);
        return events;
    }

    /**
     * Same durability boundary as OrderCommandHandler.enqueueOutbox, for the
     * fill/reject/cancel side: a rollup can touch several ancestors in one
     * call, and every one of them needs to survive a crash between here and
     * the outbox flush, not just the order the venue update was about.
     */
    private void enqueueOutbox(List<OrderDomainEvent> events) {
        for (OrderDomainEvent event : events) {
            asyncDbWriter.enqueue(new OrderOutboxRecord(ordersTopic, KafkaRoutingKeys.orderEvent(event),
                    OrderOutboxRecord.PayloadType.ORDER_EVENT, SbeEncoderDecoder.encodeOrderDomainEvent(event)));
        }
    }

    private void applyFill(TradingOrder order, ExecutionCommand command, List<OrderDomainEvent> result) {
        if (order.getStatus() == OrderStatus.FILLED || order.getStatus() == OrderStatus.REJECTED) return;

        applyFillAndRecord(order, command.commandId(), command.executionReference(),
                command.quantity(), command.price(), command.quantityScaled(), command.priceScaled(),
                command.venue(), command.occurredAt(),
                "Execution " + command.executionReference() + " received from " + command.venue(), result);

        TradingOrder child = order;
        UUID parentId = child.getParentOrderId();
        while (parentId != null) {
            // Ancestor rollup walk: parent lookup expected to be warm in cache.
            TradingOrder parent = cache.findByIdAndDeskId(parentId, child.getDeskId())
                    .orElseThrow(() -> new IllegalStateException("Parent order was not found on its desk"));
            String rollupReference = rollupReference(command.executionReference(), parent.getId());
            if (!executions.existsByExecutionReference(rollupReference)) {
                applyFillAndRecord(parent, command.commandId(), rollupReference,
                        command.quantity(), command.price(), command.quantityScaled(), command.priceScaled(),
                        command.venue(), command.occurredAt(),
                        "Child execution " + command.executionReference() + " rolled up from " + child.getId(),
                        result);
            }
            child = parent;
            parentId = parent.getParentOrderId();
        }

        if (isTerminal(order)) completePendingAncestors(order.getParentOrderId(), command, result);
    }

    private void applyFillAndRecord(TradingOrder order, UUID commandId, String reference,
                                    BigDecimal quantity, BigDecimal price,
                                    long quantityScaled, long priceScaled, String venue,
                                    java.time.Instant occurredAt, String message,
                                    List<OrderDomainEvent> result) {
        order.applyFill(quantityScaled, priceScaled);
        executions.save(new Execution(
                deterministic(reference),
                reference,
                order,
                quantity,
                price,
                venue,
                occurredAt
        ));
        cache.put(order);
        asyncDbWriter.enqueue(order);
        addEvent(commandId, order,
                order.getStatus() == OrderStatus.FILLED ? "FILLED"
                        : order.getStatus() == OrderStatus.CANCELLED ? "CANCELLED_FILL" : "PARTIALLY_FILLED",
                message, result);
        if (order.getStatus() == OrderStatus.FILLED) metrics.orderFilled();
        if (order.getStatus() == OrderStatus.CANCELLED) metrics.orderCancelled();
    }

    private void reject(TradingOrder order, ExecutionCommand command, List<OrderDomainEvent> result) {
        if (isTerminal(order)) return;
        order.reject(command.detail());
        cache.put(order);
        asyncDbWriter.enqueue(order);
        addEvent(command.commandId(), order, "REJECTED", command.detail(), result);
        metrics.orderRejected();
        completePendingAncestors(order.getParentOrderId(), command, result);
    }

    private void confirmCancel(TradingOrder order, ExecutionCommand command, List<OrderDomainEvent> result) {
        if (isTerminal(order)) return;

        // A strategy parent is acknowledged only after every child is terminal.
        // The execution service sends an acknowledgement when its scheduler is
        // stopped; child venue acknowledgements then complete the parent.
        if (!"DMA".equalsIgnoreCase(order.getDestination())
                && !orders.findByParentOrderIdAndStatusIn(order.getId(), ACTIVE).isEmpty()) {
            return;
        }

        order.confirmCancel();
        cache.put(order);
        asyncDbWriter.enqueue(order);
        addEvent(command.commandId(), order, "CANCELLED",
                blankToDefault(command.detail(), "Execution venue confirmed cancellation"), result);
        metrics.orderCancelled();
        completePendingAncestors(order.getParentOrderId(), command, result);
    }

    private void completePendingAncestors(UUID parentId, ExecutionCommand command,
                                          List<OrderDomainEvent> result) {
        UUID currentId = parentId;
        while (currentId != null) {
            // Ancestor walk: cache hit expected since child was just written above.
            TradingOrder parent = cache.findByIdAndDeskId(currentId, command.deskId())
                    .orElseThrow(() -> new IllegalStateException("Parent order was not found on its desk"));
            if (parent.getTargetStatus() != OrderStatus.CANCELLED || isTerminal(parent)
                    || !orders.findByParentOrderIdAndStatusIn(parent.getId(), ACTIVE).isEmpty()) {
                return;
            }
            parent.confirmCancel();
            cache.put(parent);
            asyncDbWriter.enqueue(parent);
            addEvent(command.commandId(), parent, "CANCELLED",
                    "All child orders reached a terminal state", result);
            metrics.orderCancelled();
            currentId = parent.getParentOrderId();
        }
    }

    private void addEvent(UUID commandId, TradingOrder order, String type, String message,
                          List<OrderDomainEvent> result) {
        OrderEvent event = new OrderEvent(commandId, order, type, message, json(order.view()));
        asyncDbWriter.enqueue(event);
        result.add(event.domainEvent());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize an execution event", exception);
        }
    }

    private static boolean isTerminal(TradingOrder order) {
        return order.getStatus() == OrderStatus.FILLED
                || order.getStatus() == OrderStatus.CANCELLED
                || order.getStatus() == OrderStatus.REJECTED;
    }

    private static String rollupReference(String childReference, UUID parentId) {
        return "ROLLUP-" + parentId + "-"
                + deterministic(childReference).toString().substring(0, 18);
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static UUID deterministic(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}

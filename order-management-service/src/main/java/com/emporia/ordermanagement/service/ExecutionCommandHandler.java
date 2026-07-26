package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.ExecutionCommand;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.ordermanagement.model.Execution;
import com.emporia.ordermanagement.model.OrderEvent;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.ExecutionRepository;
import com.emporia.ordermanagement.repository.OrderEventRepository;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
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

    ExecutionCommandHandler(TradingOrderRepository orders, ExecutionRepository executions,
                            OrderEventRepository events, ObjectMapper objectMapper) {
        this.orders = orders;
        this.executions = executions;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    /**
     * Applies a venue update and every resulting parent transition in one
     * database transaction. This makes partial-fill roll-up durable and lets an
     * optimistic-lock conflict retry the whole Kafka record without double
     * counting an execution.
     */
    @Transactional
    List<OrderDomainEvent> handle(ExecutionCommand command) {
        if (command.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported execution command schema version");
        }
        if (command.commandType() == com.emporia.events.TradingEvents.ExecutionCommandType.FILL
                && executions.existsByExecutionReference(command.executionReference())) {
            return List.of();
        }

        TradingOrder order = orders.findByIdAndDeskId(command.orderId(), command.deskId())
                .orElseThrow(() -> new IllegalArgumentException("Execution order was not found on its desk"));
        List<OrderDomainEvent> result = new ArrayList<>();

        switch (command.commandType()) {
            case FILL -> applyFill(order, command, result);
            case REJECT -> reject(order, command, result);
            case CANCEL -> confirmCancel(order, command, result);
            default -> throw new IllegalArgumentException("Unsupported execution command");
        }
        return List.copyOf(result);
    }

    private void applyFill(TradingOrder order, ExecutionCommand command, List<OrderDomainEvent> result) {
        if (order.getStatus() == OrderStatus.FILLED || order.getStatus() == OrderStatus.REJECTED) return;

        applyFillAndRecord(order, command.commandId(), command.executionReference(),
                command.quantity(), command.price(), command.venue(), command.occurredAt(),
                "Execution " + command.executionReference() + " received from " + command.venue(), result);

        TradingOrder child = order;
        UUID parentId = child.getParentOrderId();
        while (parentId != null) {
            TradingOrder parent = orders.findByIdAndDeskId(parentId, child.getDeskId())
                    .orElseThrow(() -> new IllegalStateException("Parent order was not found on its desk"));
            String rollupReference = rollupReference(command.executionReference(), parent.getId());
            if (!executions.existsByExecutionReference(rollupReference)) {
                applyFillAndRecord(parent, command.commandId(), rollupReference,
                        command.quantity(), command.price(), command.venue(), command.occurredAt(),
                        "Child execution " + command.executionReference() + " rolled up from " + child.getId(),
                        result);
            }
            child = parent;
            parentId = parent.getParentOrderId();
        }

        if (isTerminal(order)) completePendingAncestors(order.getParentOrderId(), command, result);
    }

    private void applyFillAndRecord(TradingOrder order, UUID commandId, String reference,
                                    BigDecimal quantity, BigDecimal price, String venue,
                                    java.time.Instant occurredAt, String message,
                                    List<OrderDomainEvent> result) {
        order.applyFill(quantity, price);
        executions.save(new Execution(
                deterministic(reference),
                reference,
                order,
                quantity,
                price,
                venue,
                occurredAt
        ));
        orders.saveAndFlush(order);
        addEvent(commandId, order,
                order.getStatus() == OrderStatus.FILLED ? "FILLED"
                        : order.getStatus() == OrderStatus.CANCELLED ? "CANCELLED_FILL" : "PARTIALLY_FILLED",
                message, result);
    }

    private void reject(TradingOrder order, ExecutionCommand command, List<OrderDomainEvent> result) {
        if (isTerminal(order)) return;
        order.reject(command.detail());
        orders.saveAndFlush(order);
        addEvent(command.commandId(), order, "REJECTED", command.detail(), result);
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
        orders.saveAndFlush(order);
        addEvent(command.commandId(), order, "CANCELLED",
                blankToDefault(command.detail(), "Execution venue confirmed cancellation"), result);
        completePendingAncestors(order.getParentOrderId(), command, result);
    }

    private void completePendingAncestors(UUID parentId, ExecutionCommand command,
                                          List<OrderDomainEvent> result) {
        UUID currentId = parentId;
        while (currentId != null) {
            TradingOrder parent = orders.findByIdAndDeskId(currentId, command.deskId())
                    .orElseThrow(() -> new IllegalStateException("Parent order was not found on its desk"));
            if (parent.getTargetStatus() != OrderStatus.CANCELLED || isTerminal(parent)
                    || !orders.findByParentOrderIdAndStatusIn(parent.getId(), ACTIVE).isEmpty()) {
                return;
            }
            parent.confirmCancel();
            orders.saveAndFlush(parent);
            addEvent(command.commandId(), parent, "CANCELLED",
                    "All child orders reached a terminal state", result);
            currentId = parent.getParentOrderId();
        }
    }

    private void addEvent(UUID commandId, TradingOrder order, String type, String message,
                          List<OrderDomainEvent> result) {
        OrderEvent event = events.save(new OrderEvent(commandId, order, type, message, json(order.view())));
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

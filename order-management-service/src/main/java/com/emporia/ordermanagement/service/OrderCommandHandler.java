package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.CancelAllView;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderCommandResult;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.ordermanagement.dto.ProcessingOutcome;
import com.emporia.ordermanagement.model.OrderEvent;
import com.emporia.ordermanagement.model.ProcessedCommand;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.OrderEventRepository;
import com.emporia.ordermanagement.repository.ProcessedCommandRepository;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;

@Service
public class OrderCommandHandler {
    private static final List<OrderStatus> CANCELLABLE = List.of(OrderStatus.LIVE, OrderStatus.PARTIALLY_FILLED);
    private final TradingOrderRepository orders;
    private final OrderEventRepository events;
    private final ProcessedCommandRepository processed;
    private final ObjectMapper objectMapper;

    public OrderCommandHandler(TradingOrderRepository orders, OrderEventRepository events,
                        ProcessedCommandRepository processed, ObjectMapper objectMapper) {
        this.orders = orders; this.events = events; this.processed = processed; this.objectMapper = objectMapper;
    }

    @Transactional
    public ProcessingOutcome handle(OrderCommand command) {
        ProcessedCommand cached = processed.findById(command.commandId()).orElse(null);
        if (cached != null) {
            return new ProcessingOutcome(cached.result(), events.findByCommandIdOrderByOccurredAtAsc(command.commandId())
                    .stream().map(OrderEvent::domainEvent).toList());
        }

        try {
            require(command.schemaVersion() == SCHEMA_VERSION, 400, "Unsupported order command schema version");
            return switch (command.commandType()) {
                case CREATE -> create(command);
                case MODIFY -> modify(command);
                case CANCEL -> cancel(command);
                case CANCEL_ALL -> cancelAll(command);
            };
        } catch (DomainProblem problem) {
            OrderCommandResult result = new OrderCommandResult(SCHEMA_VERSION, command.commandId(), false,
                    problem.status, problem.getMessage(), null);
            processed.save(new ProcessedCommand(result));
            return new ProcessingOutcome(result, List.of());
        }
    }

    private ProcessingOutcome create(OrderCommand command) {
        require(command.orderId() != null && command.listing() != null && command.side() != null
                && command.orderType() != null, 400, "Create command is incomplete");
        require(!orders.existsById(command.orderId()), 409, "Order already exists");
        String deskId = desk(command);
        validateQuantity(command.quantity(), command.listing().sizeIncrement(), BigDecimal.ZERO);
        BigDecimal price = validatePrice(command.orderType(), command.limitPrice(), command.listing().tickSize());
        TradingOrder parent = command.parentOrderId() == null ? null : findOnDesk(deskId, command.parentOrderId());
        if (parent != null) {
            requireCancellable(parent);
            require(parent.getTargetStatus() != OrderStatus.CANCELLED, 409,
                    "Cannot create a child for an order pending cancellation");
        }
        TradingOrder order = new TradingOrder(command.orderId(), command.userSubject(), deskId,
                command.listing(), command.side(),
                command.orderType(), command.quantity(), price, command.destination(), command.originatorReference(),
                parent == null ? null : parent.getId(), parent == null ? null : parent.getRootOrderId(), json(command.executionParameters()));
        orders.saveAndFlush(order);
        return success(command, order, "CREATED", "Order accepted by Emporia", 201);
    }

    private ProcessingOutcome modify(OrderCommand command) {
        TradingOrder order = findOnDesk(desk(command), command.orderId());
        requireCancellable(order);
        require(order.getTargetStatus() != OrderStatus.CANCELLED, 409,
                "Orders pending cancellation cannot be modified");
        require("DMA".equalsIgnoreCase(order.getDestination()), 409,
                "SMART and VWAP strategy orders do not support modification; cancel and replace the order");
        require(command.expectedVersion() != null && order.getVersion().equals(command.expectedVersion()), 409,
                "Order changed since it was loaded; refresh before modifying it");
        validateQuantity(command.quantity(), order.getListing().getSizeIncrement(), order.getTradedQuantity());
        BigDecimal price = validatePrice(order.getType(), command.limitPrice(), order.getListing().getTickSize());
        order.modify(command.quantity(), price);
        orders.saveAndFlush(order);
        return success(command, order, "MODIFIED", "Quantity or price changed", 200);
    }

    private ProcessingOutcome cancel(OrderCommand command) {
        TradingOrder order = findOnDesk(desk(command), command.orderId());
        requireCancellable(order);
        require(order.getTargetStatus() != OrderStatus.CANCELLED, 409,
                "Order cancellation is already pending");
        List<OrderDomainEvent> domainEvents = new ArrayList<>();
        requestChildCancellations(command, order.getId(), domainEvents);
        order.requestCancel();
        orders.saveAndFlush(order);
        OrderEvent parentEvent = events.save(new OrderEvent(command.commandId(), order, "CANCEL_REQUESTED",
                "Cancellation requested by user", json(order.view())));
        domainEvents.add(parentEvent.domainEvent());
        OrderCommandResult result = new OrderCommandResult(SCHEMA_VERSION, command.commandId(), true, 200,
                null, json(order.view()));
        processed.save(new ProcessedCommand(result));
        return new ProcessingOutcome(result, domainEvents);
    }

    private void requestChildCancellations(OrderCommand command, java.util.UUID parentId,
                                           List<OrderDomainEvent> domainEvents) {
        for (TradingOrder child : orders.findByParentOrderIdAndStatusIn(parentId, CANCELLABLE)) {
            requestChildCancellations(command, child.getId(), domainEvents);
            if (child.getTargetStatus() == OrderStatus.CANCELLED) continue;
            child.requestCancel();
            orders.saveAndFlush(child);
            OrderEvent childEvent = events.save(new OrderEvent(command.commandId(), child, "CANCEL_REQUESTED",
                    "Cancellation requested with parent order", json(child.view())));
            domainEvents.add(childEvent.domainEvent());
        }
    }

    private ProcessingOutcome cancelAll(OrderCommand command) {
        List<OrderDomainEvent> domainEvents = new ArrayList<>();
        for (TradingOrder order : orders.findByDeskIdAndStatusInOrderByCreatedAtDesc(desk(command), CANCELLABLE)) {
            if (order.getTargetStatus() == OrderStatus.CANCELLED) continue;
            order.requestCancel();
            orders.saveAndFlush(order);
            String payload = json(order.view());
            OrderEvent event = events.save(new OrderEvent(command.commandId(), order, "CANCEL_REQUESTED",
                    "Cancellation requested by user using cancel all", payload));
            domainEvents.add(event.domainEvent());
        }
        String payload = json(new CancelAllView(domainEvents.size()));
        OrderCommandResult result = new OrderCommandResult(SCHEMA_VERSION, command.commandId(), true, 200, null, payload);
        processed.save(new ProcessedCommand(result));
        return new ProcessingOutcome(result, domainEvents);
    }

    private ProcessingOutcome success(OrderCommand command, TradingOrder order, String type, String message, int status) {
        String payload = json(order.view());
        OrderEvent event = events.save(new OrderEvent(command.commandId(), order, type, message, payload));
        OrderCommandResult result = new OrderCommandResult(SCHEMA_VERSION, command.commandId(), true, status, null, payload);
        processed.save(new ProcessedCommand(result));
        return new ProcessingOutcome(result, List.of(event.domainEvent()));
    }

    private String desk(OrderCommand command) {
        String deskId = command.deskId();
        if (deskId == null || deskId.isBlank()) deskId = command.userSubject();
        require(deskId != null && !deskId.isBlank(), 400, "Order desk is required");
        return deskId;
    }

    private TradingOrder findOnDesk(String deskId, java.util.UUID orderId) {
        require(orderId != null, 400, "Order id is required");
        return orders.findByIdAndDeskId(orderId, deskId)
                .orElseThrow(() -> new DomainProblem(404, "Order not found"));
    }

    private void requireCancellable(TradingOrder order) {
        require(CANCELLABLE.contains(order.getStatus()), 409, "Only live or partially filled orders can be changed");
    }

    private void validateQuantity(BigDecimal quantity, BigDecimal increment, BigDecimal traded) {
        require(quantity != null && quantity.signum() > 0, 400, "Quantity must be greater than zero");
        require(quantity.compareTo(traded) > 0, 400,
                "Quantity must be greater than the quantity already traded");
        require(increment != null && increment.signum() > 0, 400,
                "Listing size increment must be greater than zero");
        require(quantity.remainder(increment).signum() == 0, 400, "Quantity must align with the listing size increment");
    }

    private BigDecimal validatePrice(OrderType type, BigDecimal price, BigDecimal tickSize) {
        require(tickSize != null && tickSize.signum() > 0, 400,
                "Listing tick size must be greater than zero");
        if (type == OrderType.MARKET) return null;
        require(price != null && price.signum() > 0, 400, "A positive limit price is required for limit orders");
        require(price.remainder(tickSize).signum() == 0, 400, "Limit price must align with the listing tick size");
        return price;
    }

    private void require(boolean condition, int status, String message) {
        if (!condition) throw new DomainProblem(status, message);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize an order event", exception);
        }
    }

    private static final class DomainProblem extends RuntimeException {
        private final int status;
        private DomainProblem(int status, String message) { super(message); this.status = status; }
    }
}

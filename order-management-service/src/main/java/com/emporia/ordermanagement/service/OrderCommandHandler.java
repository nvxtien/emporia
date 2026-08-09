package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.CancelAllView;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderCommandResult;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.risk.OrderRiskChecks;
import com.emporia.events.KafkaRoutingKeys;
import com.emporia.events.sbe.SbeEncoderDecoder;
import com.emporia.ordermanagement.dto.ProcessingOutcome;
import com.emporia.ordermanagement.model.OrderEvent;
import com.emporia.ordermanagement.model.OrderOutboxRecord;
import com.emporia.ordermanagement.model.ProcessedCommand;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.OrderEventRepository;
import com.emporia.ordermanagement.repository.ProcessedCommandRepository;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;

@Service
public class OrderCommandHandler {
    private static final List<OrderStatus> CANCELLABLE = List.of(OrderStatus.LIVE, OrderStatus.PARTIALLY_FILLED);
    private final TradingOrderRepository orders;
    private final OrderEventRepository events;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observations;
    private final OrderMetrics metrics;
    private final OrderStateCache cache;
    private final AsyncDbWriter asyncDbWriter;
    private final String ordersTopic;
    private final String resultsTopic;

    public OrderCommandHandler(TradingOrderRepository orders, OrderEventRepository events,
                        ProcessedCommandRepository processed, ObjectMapper objectMapper,
                        ObservationRegistry observations, OrderMetrics metrics, OrderStateCache cache,
                        AsyncDbWriter asyncDbWriter,
                        @Value("${emporia.kafka.orders-topic:emporia.orders.v1}") String ordersTopic,
                        @Value("${emporia.kafka.results-topic:emporia.order.results.v1}") String resultsTopic) {
        this.orders = orders; this.events = events;
        this.objectMapper = objectMapper;
        this.observations = observations; this.metrics = metrics; this.cache = cache;
        this.asyncDbWriter = asyncDbWriter;
        this.ordersTopic = ordersTopic;
        this.resultsTopic = resultsTopic;
    }

    /**
     * Executes order command handling on the single-writer Disruptor thread.
     * Direct Java invocation without Spring AOP / CGLIB proxy reflection.
     */
    public ProcessingOutcome handle(OrderCommand command) {
        Observation observation = Observation.createNotStarted("emporia.oms.command.handle", observations)
                .lowCardinalityKeyValue("command_type", commandTypeTag(command))
                .start();
        String outcome = "success";
        try {
            // Cache-backed idempotency check: avoids a DB SELECT on every command.
            ProcessedCommand cached = cache.findProcessedById(command.commandId()).orElse(null);
            if (cached != null) {
                outcome = "duplicate";
                return new ProcessingOutcome(cached.result(), events.findByCommandIdOrderByOccurredAtAsc(command.commandId())
                        .stream().map(OrderEvent::domainEvent).toList());
            }

            try {
                require(command.schemaVersion() == SCHEMA_VERSION, 400, "Unsupported order command schema version");
                ProcessingOutcome result = switch (command.commandType()) {
                    case CREATE -> create(command);
                    case MODIFY -> modify(command);
                    case CANCEL -> cancel(command);
                    case CANCEL_ALL -> cancelAll(command);
                };
                enqueueOutbox(command, result);
                return result;
            } catch (DomainProblem problem) {
                outcome = "rejected";
                OrderCommandResult result = new OrderCommandResult(SCHEMA_VERSION, command.commandId(), false,
                        problem.status, problem.getMessage(), null);
                ProcessedCommand processedCmd = new ProcessedCommand(result);
                cache.putProcessed(processedCmd);
                asyncDbWriter.enqueue(processedCmd);
                return new ProcessingOutcome(result, List.of());
            }
        } catch (RuntimeException exception) {
            outcome = "error";
            observation.error(exception);
            throw exception;
        } finally {
            observation.lowCardinalityKeyValue("outcome", outcome).stop();
        }
    }

    private static String commandTypeTag(OrderCommand command) {
        return command.commandType() == null ? "none"
                : command.commandType().name().toLowerCase(Locale.ROOT);
    }

    /**
     * Decides what this command makes eligible for Kafka, in the same place and
     * at the same time as the rows that make it durable, so a crash before the
     * outbox is flushed - including one during WAL replay, which calls
     * {@code handle} directly - cannot leave a durable order nobody was told
     * about. Only on success: a rejection never reaches Kafka today either.
     */
    private void enqueueOutbox(OrderCommand command, ProcessingOutcome outcome) {
        if (!outcome.result().success()) return;
        for (OrderDomainEvent event : outcome.events()) {
            asyncDbWriter.enqueue(new OrderOutboxRecord(ordersTopic, KafkaRoutingKeys.orderEvent(event),
                    OrderOutboxRecord.PayloadType.ORDER_EVENT, SbeEncoderDecoder.encodeOrderDomainEvent(event)));
        }
        asyncDbWriter.enqueue(new OrderOutboxRecord(resultsTopic, KafkaRoutingKeys.orderResult(command),
                OrderOutboxRecord.PayloadType.ORDER_RESULT, SbeEncoderDecoder.encodeOrderCommandResult(outcome.result())));
    }

    private ProcessingOutcome create(OrderCommand command) {
        require(command.orderId() != null && command.listing() != null && command.side() != null
                && command.orderType() != null, 400, "Create command is incomplete");
        // Cache-backed duplicate order guard: avoids a DB SELECT on CREATE.
        require(!cache.existsById(command.orderId()), 409, "Order already exists");
        String deskId = desk(command);
        long priceScaled = checkOrderRiskScaled(command.orderType(), command.quantityScaled(),
                command.listing().sizeIncrementScaled(), 0L,
                command.limitPriceScaled(), command.listing().tickSizeScaled());
        TradingOrder parent = command.parentOrderId() == null ? null : findOnDesk(deskId, command.parentOrderId());
        if (parent != null) {
            requireCancellable(parent);
            require(parent.getTargetStatus() != OrderStatus.CANCELLED, 409,
                    "Cannot create a child for an order pending cancellation");
        }
        TradingOrder order = new TradingOrder(command.orderId(), command.userSubject(), deskId,
                command.listing(), command.side(),
                command.orderType(), command.quantityScaled(), priceScaled, command.destination(), command.originatorReference(),
                parent == null ? null : parent.getId(), parent == null ? null : parent.getRootOrderId(), json(command.executionParameters()));
        cache.put(order);
        asyncDbWriter.enqueue(order);
        metrics.orderCreated();
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
        long currentTradedQtyScaled = com.emporia.events.math.FixedPointMath.toScaledLong(order.getTradedQuantity());
        long priceScaled = checkOrderRiskScaled(order.getType(), command.quantityScaled(),
                order.getListing().getSizeIncrementScaled(), currentTradedQtyScaled,
                command.limitPriceScaled(), order.getListing().getTickSizeScaled());
        order.modify(command.quantityScaled(), priceScaled);
        cache.put(order);
        asyncDbWriter.enqueue(order);
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
        cache.put(order);
        asyncDbWriter.enqueue(order);
        metrics.cancelRequested();
        OrderEvent parentEvent = new OrderEvent(command.commandId(), order, "CANCEL_REQUESTED",
                "Cancellation requested by user", json(order.view()));
        asyncDbWriter.enqueue(parentEvent);
        domainEvents.add(parentEvent.domainEvent());
        OrderCommandResult result = new OrderCommandResult(SCHEMA_VERSION, command.commandId(), true, 200,
                null, json(order.view()));
        ProcessedCommand processedCmd = new ProcessedCommand(result);
        cache.putProcessed(processedCmd);
        asyncDbWriter.enqueue(processedCmd);
        return new ProcessingOutcome(result, domainEvents);
    }

    private void requestChildCancellations(OrderCommand command, java.util.UUID parentId,
                                           List<OrderDomainEvent> domainEvents) {
        for (TradingOrder child : orders.findByParentOrderIdAndStatusIn(parentId, CANCELLABLE)) {
            requestChildCancellations(command, child.getId(), domainEvents);
            if (child.getTargetStatus() == OrderStatus.CANCELLED) continue;
            child.requestCancel();
            cache.put(child);
            asyncDbWriter.enqueue(child);
            metrics.cancelRequested();
            OrderEvent childEvent = new OrderEvent(command.commandId(), child, "CANCEL_REQUESTED",
                    "Cancellation requested with parent order", json(child.view()));
            asyncDbWriter.enqueue(childEvent);
            domainEvents.add(childEvent.domainEvent());
        }
    }

    private ProcessingOutcome cancelAll(OrderCommand command) {
        List<OrderDomainEvent> domainEvents = new ArrayList<>();
        for (TradingOrder order : orders.findByDeskIdAndStatusInOrderByCreatedAtDesc(desk(command), CANCELLABLE)) {
            if (order.getTargetStatus() == OrderStatus.CANCELLED) continue;
            order.requestCancel();
            cache.put(order);
            asyncDbWriter.enqueue(order);
            metrics.cancelRequested();
            String payload = json(order.view());
            OrderEvent event = new OrderEvent(command.commandId(), order, "CANCEL_REQUESTED",
                    "Cancellation requested by user using cancel all", payload);
            asyncDbWriter.enqueue(event);
            domainEvents.add(event.domainEvent());
        }
        String payload = json(new CancelAllView(domainEvents.size()));
        OrderCommandResult result = new OrderCommandResult(SCHEMA_VERSION, command.commandId(), true, 200, null, payload);
        ProcessedCommand processedCmd = new ProcessedCommand(result);
        cache.putProcessed(processedCmd);
        asyncDbWriter.enqueue(processedCmd);
        return new ProcessingOutcome(result, domainEvents);
    }

    private ProcessingOutcome success(OrderCommand command, TradingOrder order, String type, String message, int status) {
        String payload = json(order.view());
        OrderEvent event = new OrderEvent(command.commandId(), order, type, message, payload);
        asyncDbWriter.enqueue(event);
        OrderCommandResult result = new OrderCommandResult(SCHEMA_VERSION, command.commandId(), true, status, null, payload);
        ProcessedCommand processedCommand = new ProcessedCommand(result);
        cache.putProcessed(processedCommand);
        asyncDbWriter.enqueue(processedCommand);
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
        // Cache-backed lookup: avoids a DB SELECT on every command for a live order.
        return cache.findByIdAndDeskId(orderId, deskId)
                .orElseThrow(() -> new DomainProblem(404, "Order not found"));
    }

    private void requireCancellable(TradingOrder order) {
        require(CANCELLABLE.contains(order.getStatus()), 409, "Only live or partially filled orders can be changed");
    }

    /**
     * The quantity/price half of {@code emporia.risk.check}; the permission half
     * lives in order-command-service. There is no risk service yet, so this is
     * the de-facto pre-trade gate and gives Phase 3 a baseline to compare against.
     *
     * <p>Phase 1_1 fixes the {@code reason} vocabulary, which has no "price"
     * value, so a tick-size rejection is reported as {@code symbol} — it is an
     * instrument-level rule.
     */
    private long checkOrderRiskScaled(OrderType type, long qtyScaled, long incrementScaled,
                                      long tradedScaled, long priceScaled, long tickScaled) {
        Observation observation = Observation.createNotStarted("emporia.risk.check", observations).start();
        OrderRiskChecks.RiskOutcome outcome = OrderRiskChecks.evaluate(type, qtyScaled, incrementScaled, tradedScaled, priceScaled, tickScaled);
        try {
            if (!outcome.allowed()) {
                throw new DomainProblem(outcome.status(), outcome.message());
            }
            return outcome.validatedPriceScaled();
        } finally {
            observation.lowCardinalityKeyValue("decision", outcome.allowed() ? "allow" : "deny")
                    .lowCardinalityKeyValue("reason", outcome.reason())
                    .stop();
        }
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

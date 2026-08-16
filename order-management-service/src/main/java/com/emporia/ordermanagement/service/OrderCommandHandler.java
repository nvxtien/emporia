package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.CancelAllView;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderCommandResult;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.risk.OrderRiskChecks;
import com.emporia.ordermanagement.dto.ProcessingOutcome;
import com.emporia.ordermanagement.model.OrderEvent;
import com.emporia.ordermanagement.model.ProcessedCommand;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
    /**
     * The last repository left on the single writer thread, and only two call
     * sites use it: the child-cancellation walk and CANCEL_ALL. Both are
     * blocking reads on a thread where a blocking call is paid for by every
     * command queued behind it. See {@code docs/HOT_PATH_JPA_PLAN.md}.
     *
     * <p>The event and processed-command repositories used to be here too. The
     * first was read once, on the duplicate path, for a caller that discarded
     * the result; the second was accepted as a constructor argument and never
     * assigned to anything. Neither is missed.
     */
    private final TradingOrderRepository orders;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observations;
    private final OrderMetrics metrics;
    private final OrderStateCache cache;
    private final AsyncDbWriter asyncDbWriter;
    private final com.emporia.execution.ShardedOrderDispatcher shardedOrderDispatcher;

    public OrderCommandHandler(TradingOrderRepository orders, ObjectMapper objectMapper,
                        ObservationRegistry observations, OrderMetrics metrics, OrderStateCache cache,
                        AsyncDbWriter asyncDbWriter) {
        this(orders, objectMapper, observations, metrics, cache, asyncDbWriter, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OrderCommandHandler(TradingOrderRepository orders, ObjectMapper objectMapper,
                        ObservationRegistry observations, OrderMetrics metrics, OrderStateCache cache,
                        AsyncDbWriter asyncDbWriter,
                        com.emporia.execution.ShardedOrderDispatcher shardedOrderDispatcher) {
        this.orders = orders;
        this.objectMapper = objectMapper;
        this.observations = observations; this.metrics = metrics; this.cache = cache;
        this.asyncDbWriter = asyncDbWriter;
        this.shardedOrderDispatcher = shardedOrderDispatcher;
    }

    /**
     * Executes order command handling on the single-writer Disruptor thread.
     * Direct Java invocation without Spring AOP / CGLIB proxy reflection.
     */
    public ProcessingOutcome handle(OrderCommand command) {
        // Timer, not Observation: measured on the single writer thread, the
        // Observation machinery cost ~0.3 ms per command whether or not the span
        // was sampled, and on a single-writer path a fixed per-event cost is
        // multiplied by the queue it builds behind it. Same metric name and tags,
        // so dashboards are unchanged; what is lost is the span.
        long handleStartNanos = System.nanoTime();
        String outcome = "success";
        try {
            // Cache-backed idempotency check: avoids a DB SELECT on every command.
            ProcessedCommand cached = cache.findProcessedById(command.commandId()).orElse(null);
            if (cached != null) {
                outcome = "duplicate";
                // No events, deliberately. This used to load the original
                // command's events from the database - a blocking JPA read on
                // the single writer thread - for a caller that never reads
                // them: OrderCommandController.viewOf answers from result() and
                // view(), and this path returns before enqueueOutbox, so
                // nothing dispatches them either.
                //
                // On a single-writer path the cost of a blocking call is not
                // paid by the command that makes it. It is paid by every
                // command queued behind it, which is why a query that is
                // merely wasteful elsewhere is a stall here.
                //
                // The one consumer that does read these events,
                // OrderShadowComparisonService, replays into a sandbox that
                // already records them in memory and reads them from there.
                return new ProcessingOutcome(cached.result(), List.of());
            }

            try {
                require(command.schemaVersion() == SCHEMA_VERSION, 400, "Unsupported order command schema version");
                ProcessingOutcome result = switch (command.commandType()) {
                    case CREATE -> create(command);
                    case MODIFY -> modify(command);
                    case CANCEL -> cancel(command);
                    case CANCEL_ALL -> cancelAll(command);
                };
                long dispatchStartNanos = System.nanoTime();
                enqueueOutbox(command, result);
                metrics.registry().timer("emporia.oms.command.dispatch")
                        .record(System.nanoTime() - dispatchStartNanos,
                                java.util.concurrent.TimeUnit.NANOSECONDS);
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
            throw exception;
        } finally {
            metrics.registry().timer("emporia.oms.command.handle",
                            "command_type", commandTypeTag(command),
                            "outcome", outcome)
                    .record(System.nanoTime() - handleStartNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private static String commandTypeTag(OrderCommand command) {
        return command.commandType() == null ? "none"
                : command.commandType().name().toLowerCase(Locale.ROOT);
    }

    /**
     * Hands each domain event this command produced to the in-process
     * dispatcher, in the same place and at the same time as the rows that
     * make the command durable, so a crash before the WAL flush - including
     * one during replay, which calls {@code handle} directly - cannot leave a
     * durable order nobody was told about. Only on success: a rejection never
     * reaches execution today either.
     */
    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void enqueueOutbox(OrderCommand command, ProcessingOutcome outcome) {
        if (!outcome.result().success()) return;
        for (OrderDomainEvent event : outcome.events()) {
            if (shardedOrderDispatcher != null) {
                shardedOrderDispatcher.dispatch(event);
            }
        }
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
        // enqueueNew, not enqueue: an order's first write is the only one whose
        // conflict proves anything, since every later write upserts over a row
        // that is meant to be there.
        asyncDbWriter.enqueueNew(order);
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
        // One serialisation, used twice: the order has not changed between the
        // event and the result, and this runs on the single writer thread.
        String payload = json(order.view());
        OrderEvent parentEvent = new OrderEvent(command.commandId(), order, "CANCEL_REQUESTED",
                "Cancellation requested by user", payload);
        asyncDbWriter.enqueue(parentEvent);
        domainEvents.add(parentEvent.domainEvent());
        OrderCommandResult result = new OrderCommandResult(SCHEMA_VERSION, command.commandId(), true, 200,
                null, payload);
        ProcessedCommand processedCmd = new ProcessedCommand(result);
        cache.putProcessed(processedCmd);
        asyncDbWriter.enqueue(processedCmd);
        return new ProcessingOutcome(result, domainEvents, order.view());
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
        CancelAllView cancelAllView = new CancelAllView(domainEvents.size());
        String payload = json(cancelAllView);
        OrderCommandResult result = new OrderCommandResult(SCHEMA_VERSION, command.commandId(), true, 200, null, payload);
        ProcessedCommand processedCmd = new ProcessedCommand(result);
        cache.putProcessed(processedCmd);
        asyncDbWriter.enqueue(processedCmd);
        return new ProcessingOutcome(result, domainEvents, cancelAllView);
    }

    private ProcessingOutcome success(OrderCommand command, TradingOrder order, String type, String message, int status) {
        long startNanos = System.nanoTime();
        try {
        String payload = json(order.view());
        OrderEvent event = new OrderEvent(command.commandId(), order, type, message, payload);
        asyncDbWriter.enqueue(event);
        OrderCommandResult result = new OrderCommandResult(SCHEMA_VERSION, command.commandId(), true, status, null, payload);
        ProcessedCommand processedCommand = new ProcessedCommand(result);
        cache.putProcessed(processedCommand);
        asyncDbWriter.enqueue(processedCommand);
        return new ProcessingOutcome(result, List.of(event.domainEvent()), order.view());
        } finally {
            metrics.registry().timer("emporia.oms.command.persist")
                    .record(System.nanoTime() - startNanos,
                            java.util.concurrent.TimeUnit.NANOSECONDS);
        }
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

    /**
     * Timed because it is the largest unaccounted piece of the writer thread's
     * work, and it runs more than once per command on the cancel paths.
     */
    private String json(Object value) {
        long startNanos = System.nanoTime();
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize an order event", exception);
        } finally {
            metrics.registry().timer("emporia.oms.command.json")
                    .record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private static final class DomainProblem extends RuntimeException {
        private final int status;
        private DomainProblem(int status, String message) { super(message); this.status = status; }
    }
}

package com.emporia.execution;

import com.emporia.events.TradingEvents.ExecutionRecoveryView;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import com.emporia.events.TradingEvents.StrategyStateView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static com.emporia.events.TradingEvents.CommandType.CREATE;
import static com.emporia.events.TradingEvents.SCHEMA_VERSION;

@Component
class ExecutionEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(ExecutionEventConsumer.class);
    private static final List<OrderStatus> ACTIVE =
            List.of(OrderStatus.LIVE, OrderStatus.PARTIALLY_FILLED);

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, Object> kafka;
    private final TradingDataClient tradingData;
    private final TaskScheduler scheduler;
    private final ExecutionVenueGateway executionVenue;
    private final ExecutionCommandPublisher executionCommands;
    private final BestVenueSelector venues = new BestVenueSelector();
    private final VwapSchedule vwapSchedule = new VwapSchedule();
    private final String orderCommandsTopic;
    private final int timeCompression;
    private final int defaultVwapBuckets;
    private final Counter routed;
    private final Counter rejected;
    private final ObservationRegistry observations;
    private final String venueMode;
    private final ConcurrentHashMap<UUID, List<ScheduledFuture<?>>> runtimes = new ConcurrentHashMap<>();
    private final AtomicBoolean recovered = new AtomicBoolean();

    ExecutionEventConsumer(ObjectMapper objectMapper, KafkaTemplate<String, Object> kafka,
                           TradingDataClient tradingData, TaskScheduler scheduler,
                           ExecutionVenueGateway executionVenue, ExecutionCommandPublisher executionCommands,
                           MeterRegistry meters, ObservationRegistry observations,
                           @Value("${emporia.kafka.commands-topic}") String orderCommandsTopic,
                           @Value("${emporia.execution.strategy-time-compression}") int timeCompression,
                           @Value("${emporia.execution.vwap-default-buckets}") int defaultVwapBuckets,
                           @Value("${emporia.execution.venue-mode:simulated}") String venueMode) {
        this.objectMapper = objectMapper;
        this.kafka = kafka;
        this.tradingData = tradingData;
        this.scheduler = scheduler;
        this.executionVenue = executionVenue;
        this.executionCommands = executionCommands;
        this.orderCommandsTopic = orderCommandsTopic;
        this.timeCompression = Math.max(1, timeCompression);
        this.defaultVwapBuckets = Math.max(1, defaultVwapBuckets);
        this.routed = meters.counter("emporia.execution.orders.routed");
        this.rejected = meters.counter("emporia.execution.orders.rejected");
        this.observations = observations;
        this.venueMode = venueMode.strip().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    /**
     * Records {@code emporia.strategy.decision} around one complete strategy
     * tick. Downstream HTTP/Kafka observations become children, so a trace shows
     * both the total decision latency and the compute/data-fetch split.
     */
    private void observeStrategyDecision(String strategy, Supplier<String> action) {
        strategyDecision(strategy, () -> new StrategyResult<Void>(action.get(), null));
    }

    private <T> T strategyDecision(String strategy, Supplier<StrategyResult<T>> action) {
        Observation observation = Observation.createNotStarted("emporia.strategy.decision", observations)
                .lowCardinalityKeyValue("strategy", strategy)
                .start();
        String outcome = "success";
        try {
            try (Observation.Scope ignored = observation.openScope()) {
                StrategyResult<T> result = action.get();
                if (result == null) return null;
                outcome = strategyOutcome(result.outcome());
                return result.value();
            }
        } catch (RuntimeException exception) {
            outcome = "smart".equals(strategy) && waitingForLiquidity(exception) ? "waiting" : "error";
            if ("error".equals(outcome)) {
                observation.error(exception);
            }
            throw exception;
        } finally {
            observation.lowCardinalityKeyValue("outcome", outcome).stop();
        }
    }

    /**
     * Records {@code emporia.execution.venue.operation}. Instrumented at the
     * call site rather than by decorating {@link ExecutionVenueGateway}, because
     * a decorator bean of the same type creates a self-injection ambiguity.
     * Exchange-core performs a synchronous disk checkpoint per operation, so
     * this observation is where that cost becomes visible.
     */
    private void venueOperation(String operation, Runnable action) {
        Observation observation = Observation.createNotStarted("emporia.execution.venue.operation", observations)
                .lowCardinalityKeyValue("venue_mode", venueMode)
                .lowCardinalityKeyValue("operation", operation)
                .start();
        String outcome = "success";
        try {
            try (Observation.Scope ignored = observation.openScope()) {
                action.run();
            }
        } catch (RuntimeException exception) {
            outcome = "error";
            observation.error(exception);
            throw exception;
        } finally {
            observation.lowCardinalityKeyValue("outcome", outcome).stop();
        }
    }

    @KafkaListener(
            topics = "${emporia.kafka.orders-topic}",
            groupId = "emporia-execution-service-v1",
            properties = "auto.offset.reset=latest"
    )
    void consume(OrderDomainEvent event) {
        OrderView order = read(event.payload());
        if ("CREATED".equals(event.eventType())) {
            try {
                start(order, new StrategyStateView(order, List.of()));
                routed.increment();
            } catch (RuntimeException routingFailure) {
                reject(order, routingFailure.getMessage());
            }
            return;
        }
        if ("MODIFIED".equals(event.eventType()) && "DMA".equalsIgnoreCase(order.destination())) {
            venueOperation("modify", () -> executionVenue.modify(order));
            return;
        }
        if ("CANCEL_REQUESTED".equals(event.eventType())) {
            if ("DMA".equalsIgnoreCase(order.destination())) {
                venueOperation("cancel", () -> executionVenue.cancel(order));
            } else {
                stopRuntime(order.id());
                executionCommands.venueCancel(order.id(), order.deskId(),
                        "STRATEGY-CANCEL-" + order.id() + ":" + order.version(),
                        order.listing().exchangeMic(), "Strategy scheduler stopped");
            }
            return;
        }
        if (order.parentOrderId() == null && isTerminal(order.status())) stopRuntime(order.id());
    }

    private void start(OrderView order, StrategyStateView state) {
        switch (order.destination().toUpperCase(java.util.Locale.ROOT)) {
            case "DMA" -> venueOperation("submit", () -> executionVenue.submit(order));
            case "SMART" -> startSmart(state);
            case "VWAP" -> startVwap(state);
            default -> throw new IllegalArgumentException("Unsupported execution destination " + order.destination());
        }
    }

    private void startSmart(StrategyStateView initial) {
        stopRuntime(initial.parent().id());
        try {
            observeStrategyDecision("smart", () -> advanceSmartState(initial));
        } catch (RuntimeException unavailableLiquidity) {
            log.info("SMART strategy {} is waiting for executable liquidity: {}",
                    initial.parent().id(), unavailableLiquidity.getMessage());
        }
        Duration period = tickPeriod();
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> advanceSmart(initial.parent().id()), Instant.now().plus(period), period);
        remember(initial.parent().id(), future);
    }

    private void advanceSmart(UUID parentId) {
        try {
            observeStrategyDecision("smart", () -> {
                StrategyStateView state = tradingData.strategy(parentId);
                if (!isExecutable(state)) {
                    stopRuntime(parentId);
                    return "noop";
                }
                return advanceSmartState(state);
            });
        } catch (RuntimeException transientFailure) {
            log.warn("SMART strategy {} will retry after routing failure: {}",
                    parentId, transientFailure.getMessage());
            stopIfNoLongerExecutable(parentId, "smart");
        }
    }

    /**
     * Stops a runtime whose parent order can no longer execute.
     *
     * <p>The executable check lives inside the try block, so any failure - and
     * the routing failure below fires on every tick while no venue has
     * liquidity - skipped it entirely. A runtime for an order that was
     * cancelled, rejected or filled therefore kept ticking until the process
     * ended.
     *
     * <p>Best-effort by design: if the state fetch itself fails we cannot tell
     * whether the order is still live, so the runtime is left alone and the
     * next tick tries again. Stopping on an unknown state would abandon healthy
     * orders during a transient order-management outage.
     */
    private void stopIfNoLongerExecutable(UUID parentId, String strategy) {
        try {
            if (!isExecutable(tradingData.strategy(parentId))) {
                log.info("Stopping {} strategy runtime {}: the parent order is no longer executable",
                        strategy, parentId);
                stopRuntime(parentId);
            }
        } catch (RuntimeException stateUnavailable) {
            // Cannot determine the order state; keep the runtime and retry.
        }
    }

    private String advanceSmartState(StrategyStateView state) {
        if (!isExecutable(state)) return "noop";
        OrderView parent = state.parent();
        BigDecimal available = available(parent, state.children());
        if (available.signum() <= 0) return "noop";

        RouteData data = routeData(parent);
        List<BestVenueSelector.RouteSlice> plan = venues.plan(
                parent.side(), parent.limitPrice(), data.listings(), data.quotes(),
                available, parent.listing().sizeIncrement());
        int firstIndex = state.children().size();
        for (int index = 0; index < plan.size(); index++) {
            BestVenueSelector.RouteSlice slice = plan.get(index);
            publishChild(parent, slice.listing(), slice.price(), slice.quantity(),
                    "SMART", firstIndex + index);
        }
        return "success";
    }

    private void startVwap(StrategyStateView initial) {
        VwapPlan plan = strategyDecision("vwap", () -> {
            VwapPlan computed = vwapPlan(initial.parent());
            stopRuntime(initial.parent().id());
            return new StrategyResult<>(advanceVwapState(initial, computed, Instant.now()), computed);
        });
        Duration period = tickPeriod();
        Instant firstTick = Instant.now().plus(period);
        if (plan.start().isAfter(firstTick)) firstTick = plan.start();
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> advanceVwap(initial.parent().id()), firstTick, period);
        remember(initial.parent().id(), future);
    }

    private void advanceVwap(UUID parentId) {
        try {
            observeStrategyDecision("vwap", () -> {
                StrategyStateView state = tradingData.strategy(parentId);
                if (!isExecutable(state)) {
                    stopRuntime(parentId);
                    return "noop";
                }
                return advanceVwapState(state, vwapPlan(state.parent()), Instant.now());
            });
        } catch (RuntimeException transientFailure) {
            // A VWAP window that has closed can never reopen, so retrying is
            // pointless: vwapPlan throws on every subsequent tick, which made
            // each expired VWAP order leak a runtime that logged and issued
            // three HTTP calls ten times a second for the life of the process.
            if (vwapWindowClosed(transientFailure)) {
                log.warn("Stopping VWAP strategy runtime {}: its execution window has closed "
                        + "with the parent order still live and unfilled", parentId);
                stopRuntime(parentId);
                return;
            }
            log.warn("VWAP strategy {} will retry after scheduling failure: {}",
                    parentId, transientFailure.getMessage());
            stopIfNoLongerExecutable(parentId, "vwap");
        }
    }

    /**
     * Recognises the closed-window failure from {@code vwapPlan}.
     *
     * <p>Matches on the message because the plan builder signals this with a
     * plain {@link IllegalArgumentException} shared with several validation
     * failures, and only this one is permanent.
     *
     * <p>Note this stops the scheduler but deliberately leaves the parent order
     * alone. Expiring or cancelling an unfilled order is a trading decision -
     * see the time-in-force discussion in rework/SMART_RUNTIME_LEAK.md - so the
     * order stays live and visible rather than being cancelled by the engine.
     */
    private static boolean vwapWindowClosed(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("end time has already passed")) return true;
        }
        return false;
    }

    private String advanceVwapState(StrategyStateView state, VwapPlan plan, Instant now) {
        if (!isExecutable(state)) return "noop";
        BigDecimal target = vwapSchedule.cumulativeTarget(plan.slices(), Duration.between(plan.start(), now));
        BigDecimal sent = state.parent().tradedQuantity().add(exposed(state.children()));
        BigDecimal due = target.subtract(sent);
        if (due.signum() <= 0) return "noop";
        if (due.compareTo(state.parent().remainingQuantity()) > 0) {
            due = state.parent().remainingQuantity();
        }

        BestVenueSelector.Selection selection = selectVenue(state.parent());
        publishChild(state.parent(), selection.listing(), selection.price(), due,
                "VWAP", state.children().size());
        return "success";
    }

    private VwapPlan vwapPlan(OrderView order) {
        JsonNode parameters = readParameters(order.executionParameters());
        Instant requestedStart;
        Instant requestedEnd;
        JsonNode startSeconds = parameters.get("utcStartTimeSecs");
        JsonNode endSeconds = parameters.get("utcEndTimeSecs");
        if (startSeconds != null && startSeconds.isNumber() && endSeconds != null && endSeconds.isNumber()) {
            requestedStart = Instant.ofEpochSecond(startSeconds.asLong());
            requestedEnd = Instant.ofEpochSecond(endSeconds.asLong());
        } else {
            int durationMinutes = integer(parameters, "durationMinutes", 30);
            if (durationMinutes <= 0) {
                throw new IllegalArgumentException("VWAP durationMinutes must be positive");
            }
            requestedStart = order.createdAt();
            requestedEnd = requestedStart.plus(Duration.ofMinutes(durationMinutes));
        }
        if (!requestedStart.isBefore(requestedEnd)) {
            throw new IllegalArgumentException("VWAP start time must be before end time");
        }
        if (!requestedEnd.isAfter(Instant.now())) {
            throw new IllegalArgumentException("VWAP end time has already passed");
        }

        int participationRate = integer(parameters, "participationRate", 10);
        if (participationRate <= 0 || participationRate > 100) {
            throw new IllegalArgumentException("VWAP participationRate must be between 1 and 100");
        }
        int requestedBuckets = integer(parameters, "buckets",
                integer(parameters, "bucketCount",
                        Math.max(defaultVwapBuckets,
                                (int) Math.ceil(100.0 / participationRate))));
        if (requestedBuckets == 0) requestedBuckets = defaultVwapBuckets;
        if (requestedBuckets < 0) throw new IllegalArgumentException("VWAP buckets cannot be negative");
        int units = order.quantity().divideToIntegralValue(order.listing().sizeIncrement()).intValueExact();
        if (requestedBuckets > units) {
            throw new IllegalArgumentException("VWAP buckets cannot exceed order quantity units");
        }

        Instant effectiveStart = compress(order.createdAt(), requestedStart);
        Instant effectiveEnd = compress(order.createdAt(), requestedEnd);
        Duration duration = Duration.between(effectiveStart, effectiveEnd);
        return new VwapPlan(effectiveStart, effectiveEnd,
                vwapSchedule.create(order.quantity(), order.listing().sizeIncrement(),
                        requestedBuckets, duration));
    }

    private Instant compress(Instant anchor, Instant requested) {
        Duration relative = Duration.between(anchor, requested);
        return anchor.plus(relative.dividedBy(timeCompression));
    }

    private BestVenueSelector.Selection selectVenue(OrderView order) {
        RouteData data = routeData(order);
        return venues.select(order.side(), order.limitPrice(), data.listings(), data.quotes());
    }

    private RouteData routeData(OrderView order) {
        List<ListingSnapshot> listings =
                tradingData.sameInstrument(order.listing().id()).stream()
                        .filter(candidate -> !"XOSR".equalsIgnoreCase(candidate.exchangeMic()))
                        .toList();
        if (listings.isEmpty() && !"XOSR".equalsIgnoreCase(order.listing().exchangeMic())) {
            listings = List.of(order.listing());
        }
        if (listings.isEmpty()) {
            throw new IllegalStateException("No executable venue listing exists for the instrument");
        }
        return new RouteData(listings,
                tradingData.quotes(listings.stream().map(ListingSnapshot::id).toList()));
    }

    private void publishChild(OrderView parent, ListingSnapshot listing, BigDecimal price,
                              BigDecimal quantity, String strategy, int index) {
        UUID childId = deterministic(parent.id() + ":" + strategy + ":" + index);
        UUID commandId = deterministic(childId + ":CREATE");
        OrderCommand child = new OrderCommand(
                SCHEMA_VERSION,
                commandId,
                CREATE,
                parent.ownerSubject(),
                parent.deskId(),
                Instant.now(),
                childId,
                null,
                listing,
                parent.side(),
                OrderType.LIMIT,
                quantity,
                price,
                "DMA",
                parent.id().toString(),
                parent.id(),
                Map.of("strategy", strategy, "slice", index)
        );
        kafka.send(orderCommandsTopic, childId.toString(), child);
    }

    @EventListener(ApplicationReadyEvent.class)
    void recoverAfterStartup() {
        scheduler.schedule(this::recover, Instant.now().plusSeconds(1));
    }

    void recover() {
        if (recovered.get()) return;
        try {
            ExecutionRecoveryView recovery = tradingData.recoverable();
            for (OrderView direct : recovery.directOrders()) {
                if (direct.targetStatus() == OrderStatus.CANCELLED) {
                    venueOperation("cancel", () -> executionVenue.cancel(direct));
                } else {
                    venueOperation("recover", () -> executionVenue.recover(direct));
                }
            }
            for (StrategyStateView strategy : recovery.strategies()) {
                if (strategy.parent().targetStatus() == OrderStatus.CANCELLED) {
                    executionCommands.venueCancel(strategy.parent().id(), strategy.parent().deskId(),
                            "STRATEGY-RECOVERY-CANCEL-" + strategy.parent().id(),
                            strategy.parent().listing().exchangeMic(),
                            "Recovered strategy was already pending cancellation");
                } else {
                    try {
                        start(strategy.parent(), strategy);
                    } catch (IllegalArgumentException invalidStrategy) {
                        reject(strategy.parent(), invalidStrategy.getMessage());
                    }
                }
            }
            recovered.set(true);
            log.info("Recovered {} direct orders and {} execution strategies",
                    recovery.directOrders().size(), recovery.strategies().size());
        } catch (RuntimeException unavailable) {
            log.warn("Execution recovery will retry: {}", unavailable.getMessage());
            scheduler.schedule(this::recover, Instant.now().plusSeconds(5));
        }
    }

    /**
     * Installs the runtime for a parent, cancelling whatever it replaces.
     *
     * <p>This previously appended, so a parent could accumulate schedulers.
     * {@code startSmart} does call {@link #stopRuntime} first, but
     * stop-schedule-remember is not atomic and the Kafka listener runs on
     * several threads: two events for the same parent both find nothing to
     * stop, both schedule, and both are then remembered. The loser was never
     * cancelled and kept ticking for the life of the process.
     *
     * <p>Measured before this fix: four SMART parents were ticking about 77
     * times a second each against a 100ms period - roughly eight schedulers per
     * order - driving ~715 outbound HTTP requests per second while routing
     * nothing. Cancelling inside the same {@code compute} makes the map hold at
     * most one runtime per parent, whichever thread wins.
     */
    private void remember(UUID parentId, ScheduledFuture<?> future) {
        if (future == null) return;
        runtimes.compute(parentId, (ignored, current) -> {
            if (current != null) current.forEach(task -> task.cancel(false));
            return List.of(future);
        });
    }

    private void stopRuntime(UUID parentId) {
        List<ScheduledFuture<?>> tasks = runtimes.remove(parentId);
        if (tasks != null) tasks.forEach(task -> task.cancel(false));
    }

    private BigDecimal available(OrderView parent, List<OrderView> children) {
        BigDecimal result = parent.remainingQuantity().subtract(exposed(children));
        return result.signum() < 0 ? BigDecimal.ZERO : result;
    }

    private BigDecimal exposed(List<OrderView> children) {
        return children.stream()
                .filter(child -> ACTIVE.contains(child.status()))
                .map(OrderView::remainingQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean isExecutable(StrategyStateView state) {
        return state != null && state.parent() != null
                && ACTIVE.contains(state.parent().status())
                && state.parent().targetStatus() != OrderStatus.CANCELLED;
    }

    private boolean isTerminal(OrderStatus status) {
        return status == OrderStatus.FILLED || status == OrderStatus.CANCELLED
                || status == OrderStatus.REJECTED;
    }

    private Duration tickPeriod() {
        long millis = Math.max(100L, 1_000L / timeCompression);
        return Duration.ofMillis(millis);
    }

    private void reject(OrderView order, String detail) {
        executionCommands.reject(order.id(), order.deskId(), "REJECT-" + order.id(),
                order.listing().exchangeMic(),
                detail == null || detail.isBlank() ? "Execution routing failed" : detail);
        rejected.increment();
    }

    private OrderView read(String payload) {
        try {
            return objectMapper.readValue(payload, OrderView.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Order event payload is invalid", exception);
        }
    }

    private JsonNode readParameters(String payload) {
        try {
            return payload == null || payload.isBlank()
                    ? objectMapper.createObjectNode() : objectMapper.readTree(payload);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Execution parameters are invalid", exception);
        }
    }

    private int integer(JsonNode parameters, String name, int defaultValue) {
        JsonNode value = parameters.get(name);
        return value == null || !value.isNumber() ? defaultValue : value.asInt();
    }

    private static UUID deterministic(String value) {
        return ExecutionCommandPublisher.deterministic(value);
    }

    private static boolean waitingForLiquidity(RuntimeException exception) {
        return exception instanceof IllegalStateException
                && exception.getMessage() != null
                && exception.getMessage().startsWith("No executable venue");
    }

    private static String strategyOutcome(String outcome) {
        if (outcome == null || outcome.isBlank()) return "success";
        return outcome.strip().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private record RouteData(List<ListingSnapshot> listings,
                             List<TradingDataClient.MarketQuote> quotes) {
    }

    private record VwapPlan(Instant start, Instant end, List<VwapSchedule.Slice> slices) {
    }

    private record StrategyResult<T>(String outcome, T value) {
    }
}

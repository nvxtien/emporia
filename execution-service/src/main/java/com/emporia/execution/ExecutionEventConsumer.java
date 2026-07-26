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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final ConcurrentHashMap<UUID, List<ScheduledFuture<?>>> runtimes = new ConcurrentHashMap<>();
    private final AtomicBoolean recovered = new AtomicBoolean();

    ExecutionEventConsumer(ObjectMapper objectMapper, KafkaTemplate<String, Object> kafka,
                           TradingDataClient tradingData, TaskScheduler scheduler,
                           ExecutionVenueGateway executionVenue, ExecutionCommandPublisher executionCommands,
                           MeterRegistry meters,
                           @Value("${emporia.kafka.commands-topic}") String orderCommandsTopic,
                           @Value("${emporia.execution.strategy-time-compression}") int timeCompression,
                           @Value("${emporia.execution.vwap-default-buckets}") int defaultVwapBuckets) {
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
            executionVenue.modify(order);
            return;
        }
        if ("CANCEL_REQUESTED".equals(event.eventType())) {
            if ("DMA".equalsIgnoreCase(order.destination())) {
                executionVenue.cancel(order);
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
            case "DMA" -> executionVenue.submit(order);
            case "SMART" -> startSmart(state);
            case "VWAP" -> startVwap(state);
            default -> throw new IllegalArgumentException("Unsupported execution destination " + order.destination());
        }
    }

    private void startSmart(StrategyStateView initial) {
        stopRuntime(initial.parent().id());
        try {
            tryAdvanceSmart(initial);
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
            StrategyStateView state = tradingData.strategy(parentId);
            if (!isExecutable(state)) {
                stopRuntime(parentId);
                return;
            }
            tryAdvanceSmart(state);
        } catch (RuntimeException transientFailure) {
            log.warn("SMART strategy {} will retry after routing failure: {}",
                    parentId, transientFailure.getMessage());
        }
    }

    private void tryAdvanceSmart(StrategyStateView state) {
        OrderView parent = state.parent();
        if (!isExecutable(state)) return;
        BigDecimal available = available(parent, state.children());
        if (available.signum() <= 0) return;

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
    }

    private void startVwap(StrategyStateView initial) {
        VwapPlan plan = vwapPlan(initial.parent());
        stopRuntime(initial.parent().id());
        tryAdvanceVwap(initial, plan, Instant.now());
        Duration period = tickPeriod();
        Instant firstTick = Instant.now().plus(period);
        if (plan.start().isAfter(firstTick)) firstTick = plan.start();
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> advanceVwap(initial.parent().id()), firstTick, period);
        remember(initial.parent().id(), future);
    }

    private void advanceVwap(UUID parentId) {
        try {
            StrategyStateView state = tradingData.strategy(parentId);
            if (!isExecutable(state)) {
                stopRuntime(parentId);
                return;
            }
            tryAdvanceVwap(state, vwapPlan(state.parent()), Instant.now());
        } catch (RuntimeException transientFailure) {
            log.warn("VWAP strategy {} will retry after scheduling failure: {}",
                    parentId, transientFailure.getMessage());
        }
    }

    private void tryAdvanceVwap(StrategyStateView state, VwapPlan plan, Instant now) {
        if (!isExecutable(state)) return;
        Duration elapsed = Duration.between(plan.start(), now);
        BigDecimal target = vwapSchedule.cumulativeTarget(plan.slices(), elapsed);
        BigDecimal sent = state.parent().tradedQuantity().add(exposed(state.children()));
        BigDecimal due = target.subtract(sent);
        if (due.signum() <= 0) return;
        if (due.compareTo(state.parent().remainingQuantity()) > 0) {
            due = state.parent().remainingQuantity();
        }

        BestVenueSelector.Selection selection = selectVenue(state.parent());
        publishChild(state.parent(), selection.listing(), selection.price(), due,
                "VWAP", state.children().size());
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
                if (direct.targetStatus() == OrderStatus.CANCELLED) executionVenue.cancel(direct);
                else executionVenue.recover(direct);
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

    private void remember(UUID parentId, ScheduledFuture<?> future) {
        if (future == null) return;
        runtimes.compute(parentId, (ignored, current) -> {
            List<ScheduledFuture<?>> next = new ArrayList<>(current == null ? List.of() : current);
            next.add(future);
            return List.copyOf(next);
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

    private record RouteData(List<ListingSnapshot> listings,
                             List<TradingDataClient.MarketQuote> quotes) {
    }

    private record VwapPlan(Instant start, Instant end, List<VwapSchedule.Slice> slices) {
    }
}

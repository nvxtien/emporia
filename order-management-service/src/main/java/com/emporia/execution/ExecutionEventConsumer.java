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
import com.emporia.strategy.sor.BestVenueSelector;
import com.emporia.strategy.vwap.VwapSchedule;
import org.springframework.context.event.EventListener;
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
    private final TradingDataClient tradingData;
    private final TaskScheduler scheduler;
    private final ExecutionVenueGateway executionVenue;
    private final ExecutionCommandPublisher executionCommands;
    private final BestVenueSelector venues = new BestVenueSelector();
    private final VwapSchedule vwapSchedule = new VwapSchedule();
    private final int timeCompression;
    private final int defaultVwapBuckets;
    private final Counter routed;
    private final Counter rejected;
    private final Counter childPublishImmediateFailures;
    private final ObservationRegistry observations;
    private final String venueMode;
    private final ConcurrentHashMap<UUID, List<ScheduledFuture<?>>> runtimes = new ConcurrentHashMap<>();
    /** Consecutive failed sweeps per parent; cleared as soon as one succeeds. */
    private final ConcurrentHashMap<UUID, Integer> sweepFailures = new ConcurrentHashMap<>();
    private final int maxSweepRetries;
    private final AtomicBoolean recovered = new AtomicBoolean();

    private final com.emporia.ordermanagement.disruptor.DisruptorOrderPipeline disruptorPipeline;
    private final com.emporia.ordermanagement.service.OrderInputEventRecorder inputRecorder;

    ExecutionEventConsumer(ObjectMapper objectMapper,
                           TradingDataClient tradingData, TaskScheduler scheduler,
                           ExecutionVenueGateway executionVenue, ExecutionCommandPublisher executionCommands,
                           MeterRegistry meters, ObservationRegistry observations,
                           @Value("${emporia.execution.strategy-time-compression}") int timeCompression,
                           @Value("${emporia.execution.vwap-default-buckets}") int defaultVwapBuckets,
                           @Value("${emporia.execution.venue-mode:simulated}") String venueMode,
                           @Value("${emporia.execution.sor.max-retries:3}") int maxSweepRetries) {
        this(objectMapper, tradingData, scheduler, executionVenue, executionCommands,
                meters, observations, timeCompression, defaultVwapBuckets,
                venueMode, maxSweepRetries, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    ExecutionEventConsumer(ObjectMapper objectMapper,
                           TradingDataClient tradingData, TaskScheduler scheduler,
                           ExecutionVenueGateway executionVenue, ExecutionCommandPublisher executionCommands,
                           MeterRegistry meters, ObservationRegistry observations,
                           @Value("${emporia.execution.strategy-time-compression}") int timeCompression,
                           @Value("${emporia.execution.vwap-default-buckets}") int defaultVwapBuckets,
                           @Value("${emporia.execution.venue-mode:simulated}") String venueMode,
                           @Value("${emporia.execution.sor.max-retries:3}") int maxSweepRetries,
                           @org.springframework.context.annotation.Lazy @org.springframework.beans.factory.annotation.Autowired(required = false) com.emporia.ordermanagement.disruptor.DisruptorOrderPipeline disruptorPipeline,
                           @org.springframework.context.annotation.Lazy @org.springframework.beans.factory.annotation.Autowired(required = false) com.emporia.ordermanagement.service.OrderInputEventRecorder inputRecorder) {
        this.objectMapper = objectMapper;
        this.tradingData = tradingData;
        this.scheduler = scheduler;
        this.executionVenue = executionVenue;
        this.executionCommands = executionCommands;
        this.timeCompression = Math.max(1, timeCompression);
        this.defaultVwapBuckets = Math.max(1, defaultVwapBuckets);
        this.routed = meters.counter("emporia.execution.orders.routed");
        this.rejected = meters.counter("emporia.execution.orders.rejected");
        this.childPublishImmediateFailures = meters.counter("emporia.execution.child.publish.failures",
            "stage", "immediate");
        this.observations = observations;
        this.venueMode = venueMode.strip().toLowerCase(Locale.ROOT).replace('-', '_');
        this.maxSweepRetries = Math.max(1, maxSweepRetries);
        this.disruptorPipeline = disruptorPipeline;
        this.inputRecorder = inputRecorder;
    }

    /**
     * Records {@code emporia.strategy.decision} around one complete strategy
     * tick. Downstream HTTP observations become children, so a trace shows
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

    void processEvent(OrderDomainEvent event) {
        OrderView order = read(event.payload());
        switch (event.eventType()) {
            case "CREATED" -> handleCreated(order);
            case "MODIFIED" -> handleModified(order);
            case "CANCEL_REQUESTED" -> handleCancelRequested(order);
            default -> handleTerminalParent(order);
        }
    }

    /**
     * Orchestration boundary: this method dispatches execution but never writes
     * order state directly; order-management remains the state authority.
     */
    private void handleCreated(OrderView order) {
        try {
            validateCreatedOrder(order);
            start(order, new StrategyStateView(order, List.of()));
            routed.increment();
        } catch (RuntimeException routingFailure) {
            reject(order, routingFailure.getMessage());
        }
    }

    /**
     * External I/O boundary: only DMA orders are modifiable at venue level.
     */
    private void handleModified(OrderView order) {
        if (isDmaDestination(order)) {
            venueOperation("modify", () -> executionVenue.modify(order));
        }
    }

    /**
     * Runtime boundary: strategy cancellation stops local schedulers and emits
     * an explicit execution command so OMS resolves parent state.
     */
    private void handleCancelRequested(OrderView order) {
        if (isDmaDestination(order)) {
            venueOperation("cancel", () -> executionVenue.cancel(order));
            return;
        }
        stopRuntime(order.id());
        executionCommands.venueCancel(order.id(), order.deskId(),
                "STRATEGY-CANCEL-" + order.id() + ":" + order.version(),
                order.listing().exchangeMic(), "Strategy scheduler stopped");
    }

    private void handleTerminalParent(OrderView order) {
        if (order.parentOrderId() == null && isTerminal(order.status())) {
            stopRuntime(order.id());
        }
    }

    /**
     * Phase 3 invariant gate for created orders. Rejects malformed input before
     * any venue call or strategy scheduling can happen.
     */
    private void validateCreatedOrder(OrderView order) {
        require(order.id() != null, "Order id is required");
        require(order.deskId() != null && !order.deskId().isBlank(), "Order desk is required");
        require(order.listing() != null, "Listing is required");
        require(order.listing().exchangeMic() != null && !order.listing().exchangeMic().isBlank(),
                "Listing exchange MIC is required");
        require(order.destination() != null && !order.destination().isBlank(),
                "Execution destination is required");
        require(order.quantity() != null && order.quantity().signum() > 0,
                "Order quantity must be positive");
        require(order.remainingQuantity() != null && order.remainingQuantity().signum() >= 0,
                "Order remaining quantity must be non-negative");
        if (order.type() == OrderType.LIMIT) {
            require(order.limitPrice() != null, "Limit order price is required");
        }
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
            if (!waitingForLiquidity(unavailableLiquidity)) throw unavailableLiquidity;
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
                String outcome = advanceSmartState(state);
                if ("success".equals(outcome)) {
                    sweepFailures.remove(parentId);
                }
                return outcome;
            });
        } catch (RuntimeException transientFailure) {
            if (retriesExhausted(parentId)) {
                giveUp(parentId, "smart",
                        "SMART routing retries exhausted after " + maxSweepRetries + " attempts");
                return;
            }
            log.warn("SMART strategy {} will retry after routing failure: {}",
                    parentId, transientFailure.getMessage());
            stopIfNoLongerExecutable(parentId, "smart");
        }
    }

    /**
     * Counts consecutive failed sweeps and reports when the ceiling is reached.
     *
     * <p>Before this there was no ceiling at all: a parent with no routable
     * liquidity retried at the full tick rate for the life of the process. The
     * runtime-leak fix reduced the cost of that but did not stop it.
     */
    private boolean retriesExhausted(UUID parentId) {
        return sweepFailures.merge(parentId, 1, Integer::sum) >= maxSweepRetries;
    }

    /**
     * Stops a strategy runtime and resolves its parent order.
     *
     * <p>A stopped runtime must not leave the parent sitting LIVE with nothing
     * working on it. The remainder action decides what happens: {@code CANCEL}
     * by default, so the order reaches a terminal state with the reason
     * recorded rather than becoming a ghost nobody is executing.
     */
    private void giveUp(UUID parentId, String strategy, String reason) {
        stopRuntime(parentId);
        sweepFailures.remove(parentId);
        strategyDecision(strategy, () -> new StrategyResult<Void>("exhausted", null));
        try {
            OrderView parent = tradingData.strategy(parentId).parent();
            if (!isTerminal(parent.status())) {
                executionCommands.venueCancel(parent.id(), parent.deskId(),
                        "STRATEGY-EXHAUSTED-" + parent.id() + ":" + parent.version(),
                        parent.listing().exchangeMic(), reason);
            }
            log.warn("Stopping {} strategy runtime {}: {}", strategy, parentId, reason);
        } catch (RuntimeException stateUnavailable) {
            // The runtime is already stopped, which is the important half. The
            // parent is left alone rather than cancelled on a guess, and
            // recovery reattaches to it after a restart.
            log.warn("Stopped {} strategy runtime {} ({}), but could not resolve the parent order: {}",
                    strategy, parentId, reason, stateUnavailable.getMessage());
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
        List<OrderView> children = state.children();
        if (lastRouteRejected(children)) {
            throw new IllegalStateException(
                    "The most recently routed SMART child order was rejected by the venue; "
                            + "not routing further slices without forward progress");
        }
        BigDecimal available = available(parent, children);
        if (available.signum() <= 0) return "noop";

        RouteData data = routeData(parent);
        List<BestVenueSelector.RouteSlice> plan = venues.plan(
                parent.side(), parent.limitPrice(), data.listings(), data.quotes(),
                available, parent.listing().sizeIncrement());
        if (plan.isEmpty()) {
            throw new IllegalStateException("No executable venue listing exists for the instrument");
        }
        int firstIndex = children.size();
        for (int index = 0; index < plan.size(); index++) {
            BestVenueSelector.RouteSlice slice = plan.get(index);
            publishChild(parent, slice.listing(), parent.limitPrice(), slice.price(), slice.quantity(),
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
                giveUp(parentId, "vwap", "VWAP execution window expired");
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
        publishChild(state.parent(), selection.listing(), state.parent().limitPrice(), selection.price(), due,
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
        List<TradingDataClient.MarketQuote> rawQuotes = tradingData.quotes(listings.stream().map(ListingSnapshot::id).toList());
        List<BestVenueSelector.MarketQuoteView> quoteViews = mapQuotes(rawQuotes);
        return new RouteData(listings, quoteViews);
    }

    private List<BestVenueSelector.MarketQuoteView> mapQuotes(List<TradingDataClient.MarketQuote> quotes) {
        if (quotes == null || quotes.isEmpty()) return List.of();
        List<BestVenueSelector.MarketQuoteView> views = new java.util.ArrayList<>();
        for (TradingDataClient.MarketQuote q : quotes) {
            Map<String, TradingDataClient.DepthLevel> bestBids = new java.util.LinkedHashMap<>();
            for (TradingDataClient.DepthLevel bid : q.bids()) {
                if (bid == null || bid.price() == null) continue;
                String mic = bid.exchangeMic() != null ? bid.exchangeMic() : "";
                TradingDataClient.DepthLevel existing = bestBids.get(mic);
                if (existing == null || bid.price().compareTo(existing.price()) > 0) {
                    bestBids.put(mic, bid);
                }
            }

            Map<String, TradingDataClient.DepthLevel> bestOffers = new java.util.LinkedHashMap<>();
            for (TradingDataClient.DepthLevel offer : q.offers()) {
                if (offer == null || offer.price() == null) continue;
                String mic = offer.exchangeMic() != null ? offer.exchangeMic() : "";
                TradingDataClient.DepthLevel existing = bestOffers.get(mic);
                if (existing == null || offer.price().compareTo(existing.price()) < 0) {
                    bestOffers.put(mic, offer);
                }
            }

            java.util.Set<String> mics = new java.util.LinkedHashSet<>();
            mics.addAll(bestBids.keySet());
            mics.addAll(bestOffers.keySet());

            for (String mic : mics) {
                TradingDataClient.DepthLevel bid = bestBids.get(mic);
                TradingDataClient.DepthLevel offer = bestOffers.get(mic);
                BigDecimal bidPrice = bid != null ? bid.price() : null;
                BigDecimal bidSize = bid != null ? bid.size() : null;
                BigDecimal askPrice = offer != null ? offer.price() : null;
                BigDecimal askSize = offer != null ? offer.size() : null;
                views.add(new BestVenueSelector.MarketQuoteView(q.listingId(), mic, bidPrice, bidSize, askPrice, askSize));
            }
        }
        return views;
    }

    /**
     * Publishes a routed child order for a strategy slice.
     *
     * <p>{@code limitPrice} is the parent's original limit price, which
     * {@link ExchangeCoreExecutionVenueGateway#protectionPriceTicks} uses as
     * the slippage anchor. Anchoring against the venue quote (the old
     * behaviour) allowed fills at quote + slippageBps even when the quote was
     * already outside the parent's budget.
     *
     * <p>{@code venuePrice} is the best-available quote at the time of routing,
     * kept in {@code executionParameters} for observability and fill
     * reconciliation but not used for risk or matching.
     */
    private void publishChild(OrderView parent, ListingSnapshot listing,
                              BigDecimal limitPrice, BigDecimal venuePrice,
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
                limitPrice,
                "DMA",
                parent.id().toString(),
                parent.id(),
                // IOC: a routed child takes what liquidity is available within
                // its slippage budget and cancels the rest, rather than resting
                // on the book. Resting children are why thousands of orders
                // accumulated, and the venue's checkpoint cost grows with the
                // book, so a child that does not fill must not linger.
                // venuePrice records the best quote at routing time for
                // observability; the protection cap is derived from limitPrice.
                Map.of("strategy", strategy, "slice", index, "tif", "IOC",
                        "venuePrice", venuePrice)
        );
        if (disruptorPipeline == null) {
            childPublishImmediateFailures.increment();
            throw new IllegalStateException("Could not publish child order command: no order pipeline is configured");
        }
        try {
            if (inputRecorder != null) {
                inputRecorder.record(child);
            }
            disruptorPipeline.submit(child);
        } catch (RuntimeException exception) {
            childPublishImmediateFailures.increment();
            throw new IllegalStateException("Could not publish child order command", exception);
        }
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
     * stop-schedule-remember is not atomic and the dispatcher runs on
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

    /**
     * Detects a stalled SMART sweep: the child(ren) from the most recent
     * routing round were all rejected by the venue.
     *
     * <p>A rejected child fills nothing, so it does not reduce {@code
     * remainingQuantity} and drops out of {@link #exposed}, which makes the
     * exact same quantity look "available" again on the next tick. Without
     * this check that quantity gets re-planned and re-rejected forever - a
     * persistently unfundable account (RISK_NSF on every attempt) was
     * observed spawning 360+ rejected children in seconds with no ceiling.
     * Routing this failure through the same {@code sweepFailures} path as
     * "no venue" bounds it to {@code maxSweepRetries} ticks before the
     * runtime gives up instead of retrying with a fresh plan, since a
     * different venue choice would not fix an account-level rejection.
     */
    private boolean lastRouteRejected(List<OrderView> children) {
        if (children.isEmpty()) return false;
        Instant latest = children.stream().map(OrderView::createdAt).max(Instant::compareTo).orElseThrow();
        return children.stream()
                .filter(child -> child.createdAt().equals(latest))
                .allMatch(child -> child.status() == OrderStatus.REJECTED);
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

    private static boolean isDmaDestination(OrderView order) {
        return "DMA".equalsIgnoreCase(order.destination());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
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
                             List<BestVenueSelector.MarketQuoteView> quotes) {
    }

    private record VwapPlan(Instant start, Instant end, List<VwapSchedule.Slice> slices) {
    }

    private record StrategyResult<T>(String outcome, T value) {
    }
}

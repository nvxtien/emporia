package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.CommandType;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import com.emporia.ordermanagement.dto.ProcessingOutcome;
import com.emporia.ordermanagement.model.OrderEvent;
import com.emporia.ordermanagement.model.ProcessedCommand;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.OrderEventRepository;
import com.emporia.ordermanagement.repository.ProcessedCommandRepository;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThat;

class TradingOrderPropertyTest {
    private static final String USER_SUBJECT = "property-test-user";
    private static final BigDecimal SIZE_INCREMENT = new BigDecimal("0.01");
    private static final BigDecimal TICK_SIZE = new BigDecimal("0.01");
    private static final Field VERSION_FIELD = field("version");
    private static final Field TRADED_QUANTITY_FIELD = field("tradedQuantity");
    private static final Field REMAINING_QUANTITY_FIELD = field("remainingQuantity");
    private static final Field STATUS_FIELD = field("status");

    @Property(tries = 300)
    void validLimitOrdersPreserveNumericInvariants(
            @ForAll @LongRange(min = 1, max = 1_000_000) long quantityLots,
            @ForAll @LongRange(min = 1, max = 10_000_000) long priceTicks
    ) {
        BigDecimal quantity = BigDecimal.valueOf(quantityLots, 2);
        BigDecimal price = BigDecimal.valueOf(priceTicks, 2);
        Fixture fixture = new Fixture();
        UUID orderId = UUID.randomUUID();

        ProcessingOutcome outcome = fixture.handler.handle(
                createCommand(UUID.randomUUID(), orderId, quantity, price)
        );

        assertThat(outcome.result().success()).isTrue();
        assertThat(outcome.result().status()).isEqualTo(201);
        OrderView order = fixture.order(orderId).view();
        assertNumericInvariants(order);
        assertThat(order.quantity()).isEqualByComparingTo(quantity);
        assertThat(order.limitPrice()).isEqualByComparingTo(price);
        assertThat(order.remainingQuantity()).isEqualByComparingTo(quantity);
        assertThat(order.tradedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(order.status()).isEqualTo(OrderStatus.LIVE);
        assertThat(order.targetStatus()).isEqualTo(OrderStatus.LIVE);
    }

    @Property(tries = 200)
    void nonPositiveOrMisalignedQuantitiesAreRejected(
            @ForAll("invalidQuantities") BigDecimal quantity
    ) {
        Fixture fixture = new Fixture();
        UUID orderId = UUID.randomUUID();

        ProcessingOutcome outcome = fixture.handler.handle(
                createCommand(UUID.randomUUID(), orderId, quantity, new BigDecimal("10.00"))
        );

        assertThat(outcome.result().success()).isFalse();
        assertThat(outcome.result().status()).isEqualTo(400);
        assertThat(fixture.hasOrder(orderId)).isFalse();
        assertThat(fixture.eventCount()).isZero();
    }

    @Property(tries = 200)
    void nonPositiveOrOffTickLimitPricesAreRejected(
            @ForAll("invalidPrices") BigDecimal price
    ) {
        Fixture fixture = new Fixture();
        UUID orderId = UUID.randomUUID();

        ProcessingOutcome outcome = fixture.handler.handle(
                createCommand(UUID.randomUUID(), orderId, new BigDecimal("100.00"), price)
        );

        assertThat(outcome.result().success()).isFalse();
        assertThat(outcome.result().status()).isEqualTo(400);
        assertThat(fixture.hasOrder(orderId)).isFalse();
        assertThat(fixture.eventCount()).isZero();
    }

    @Property(tries = 250)
    void partiallyFilledOrdersPreserveAccountingAndCannotBeReducedBelowTradedQuantity(
            @ForAll @LongRange(min = 1, max = 500_000) long tradedLots,
            @ForAll @LongRange(min = 1, max = 500_000) long initialRemainingLots,
            @ForAll @LongRange(min = 1, max = 500_000) long modifiedRemainingLots,
            @ForAll @LongRange(min = 1, max = 10_000_000) long priceTicks
    ) {
        BigDecimal traded = BigDecimal.valueOf(tradedLots, 2);
        BigDecimal initialQuantity = BigDecimal.valueOf(tradedLots + initialRemainingLots, 2);
        BigDecimal modifiedQuantity = BigDecimal.valueOf(tradedLots + modifiedRemainingLots, 2);
        BigDecimal modifiedPrice = BigDecimal.valueOf(priceTicks, 2);
        Fixture fixture = new Fixture();
        UUID orderId = UUID.randomUUID();
        fixture.handler.handle(
                createCommand(UUID.randomUUID(), orderId, initialQuantity, new BigDecimal("25.00"))
        );
        fixture.simulatePartialFill(orderId, traded);

        TradingOrder partiallyFilled = fixture.order(orderId);
        ProcessingOutcome modified = fixture.handler.handle(
                modifyCommand(
                        UUID.randomUUID(),
                        orderId,
                        partiallyFilled.getVersion(),
                        modifiedQuantity,
                        modifiedPrice
                )
        );

        assertThat(modified.result().success()).isTrue();
        OrderView afterModify = fixture.order(orderId).view();
        assertNumericInvariants(afterModify);
        assertThat(afterModify.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(afterModify.tradedQuantity()).isEqualByComparingTo(traded);
        assertThat(afterModify.remainingQuantity())
                .isEqualByComparingTo(BigDecimal.valueOf(modifiedRemainingLots, 2));

        for (BigDecimal invalidQuantity : List.of(traded, traded.subtract(SIZE_INCREMENT))) {
            ProcessingOutcome rejected = fixture.handler.handle(
                    modifyCommand(
                            UUID.randomUUID(),
                            orderId,
                            fixture.order(orderId).getVersion(),
                            invalidQuantity,
                            modifiedPrice
                    )
            );

            assertThat(rejected.result().success()).isFalse();
            assertThat(rejected.result().status()).isEqualTo(400);
            OrderView afterRejectedModify = fixture.order(orderId).view();
            assertSameOrderState(afterRejectedModify, afterModify);
            assertNumericInvariants(afterRejectedModify);
        }
    }

    @Property(tries = 250)
    void randomizedModifyCancelSequencesPreserveStateMachineInvariants(
            @ForAll("commandSequences") List<CommandStep> sequence
    ) {
        Fixture fixture = new Fixture();
        UUID orderId = UUID.randomUUID();
        ProcessingOutcome created = fixture.handler.handle(
                createCommand(UUID.randomUUID(), orderId, new BigDecimal("100.00"), new BigDecimal("25.00"))
        );
        assertThat(created.result().success()).isTrue();

        OrderView terminalSnapshot = null;
        for (CommandStep step : sequence) {
            TradingOrder current = fixture.order(orderId);
            OrderView before = current.view();
            OrderCommand command = switch (step.action()) {
                case MODIFY -> modifyCommand(
                        UUID.randomUUID(),
                        orderId,
                        current.getVersion(),
                        BigDecimal.valueOf(step.quantityLots(), 2),
                        BigDecimal.valueOf(step.priceTicks(), 2)
                );
                case CANCEL -> cancelCommand(UUID.randomUUID(), orderId);
            };

            ProcessingOutcome outcome = fixture.handler.handle(command);
            OrderView after = fixture.order(orderId).view();
            assertNumericInvariants(after);

            if (before.targetStatus() == OrderStatus.CANCELLED) {
                assertThat(outcome.result().success()).isFalse();
                assertThat(outcome.result().status()).isEqualTo(409);
                assertUnchanged(after, terminalSnapshot);
                continue;
            }

            assertThat(outcome.result().success()).isTrue();
            if (step.action() == Action.CANCEL) {
                assertThat(after.status()).isEqualTo(OrderStatus.LIVE);
                assertThat(after.targetStatus()).isEqualTo(OrderStatus.CANCELLED);
                terminalSnapshot = after;
            } else {
                assertThat(after.status()).isEqualTo(OrderStatus.LIVE);
                assertThat(after.targetStatus()).isEqualTo(OrderStatus.LIVE);
                assertThat(after.quantity()).isEqualByComparingTo(BigDecimal.valueOf(step.quantityLots(), 2));
                assertThat(after.limitPrice()).isEqualByComparingTo(BigDecimal.valueOf(step.priceTicks(), 2));
            }
        }
    }

    @Property(tries = 400)
    void twoFillWeightedAveragesConserveQuantity(
            @ForAll @LongRange(min = 2, max = 1_000_000) long quantityLots,
            @ForAll long splitSeed,
            @ForAll @LongRange(min = 1, max = 10_000_000) long firstPriceTicks,
            @ForAll @LongRange(min = 1, max = 10_000_000) long secondPriceTicks
    ) {
        long firstLots = 1 + Math.floorMod(splitSeed, quantityLots - 1);
        long secondLots = quantityLots - firstLots;
        BigDecimal quantity = BigDecimal.valueOf(quantityLots, 2);
        BigDecimal firstQuantity = BigDecimal.valueOf(firstLots, 2);
        BigDecimal secondQuantity = BigDecimal.valueOf(secondLots, 2);
        BigDecimal firstPrice = BigDecimal.valueOf(firstPriceTicks, 2);
        BigDecimal secondPrice = BigDecimal.valueOf(secondPriceTicks, 2);
        TradingOrder order = aggregate(quantity, new BigDecimal("25.00"));

        order.applyFill(firstQuantity, firstPrice);
        assertLifecycleInvariants(order.view());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);

        order.applyFill(secondQuantity, secondPrice);

        BigDecimal expectedAverage = firstPrice.multiply(firstQuantity)
                .add(secondPrice.multiply(secondQuantity))
                .divide(quantity, 6, RoundingMode.HALF_UP);
        OrderView filled = order.view();
        assertLifecycleInvariants(filled);
        assertThat(filled.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(filled.targetStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(filled.tradedQuantity()).isEqualByComparingTo(quantity);
        assertThat(filled.remainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(filled.averageTradePrice()).isEqualByComparingTo(expectedAverage);
    }

    @Property(tries = 350)
    void cancelFillRacesPreserveLateExecutions(
            @ForAll @LongRange(min = 2, max = 1_000_000) long quantityLots,
            @ForAll long preFillSeed,
            @ForAll long raceFillSeed,
            @ForAll boolean venueAcknowledgesFirst,
            @ForAll @LongRange(min = 1, max = 10_000_000) long fillPriceTicks
    ) {
        long preFillLots = Math.floorMod(preFillSeed, quantityLots);
        long remainingLots = quantityLots - preFillLots;
        long raceFillLots = 1 + Math.floorMod(raceFillSeed, remainingLots);
        TradingOrder order = aggregate(BigDecimal.valueOf(quantityLots, 2), new BigDecimal("25.00"));
        if (preFillLots > 0) {
            order.applyFill(BigDecimal.valueOf(preFillLots, 2), new BigDecimal("24.50"));
        }

        order.requestCancel();
        assertThat(order.getTargetStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getStatus()).isIn(OrderStatus.LIVE, OrderStatus.PARTIALLY_FILLED);

        if (venueAcknowledgesFirst) {
            order.confirmCancel();
        }
        order.applyFill(BigDecimal.valueOf(raceFillLots, 2), BigDecimal.valueOf(fillPriceTicks, 2));
        if (!venueAcknowledgesFirst && order.getStatus() != OrderStatus.FILLED) {
            order.confirmCancel();
        }

        OrderView result = order.view();
        assertLifecycleInvariants(result);
        assertThat(result.tradedQuantity())
                .isEqualByComparingTo(BigDecimal.valueOf(preFillLots + raceFillLots, 2));
        if (preFillLots + raceFillLots == quantityLots) {
            assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(result.targetStatus()).isEqualTo(OrderStatus.FILLED);
        } else {
            assertThat(result.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(result.targetStatus()).isEqualTo(OrderStatus.CANCELLED);
        }
    }

    @Property(tries = 350)
    void randomizedVenueEventSequencesPreserveLifecycleInvariants(
            @ForAll("lifecycleSequences") List<LifecycleStep> sequence
    ) {
        TradingOrder order = aggregate(new BigDecimal("100.00"), new BigDecimal("25.00"));

        for (LifecycleStep step : sequence) {
            OrderView before = order.view();
            boolean transitionAllowed = transitionAllowed(before, step);

            try {
                apply(order, step);
                assertThat(transitionAllowed)
                        .as("transition %s should have been rejected from %s/%s",
                                step.action(), before.status(), before.targetStatus())
                        .isTrue();
                assertTransition(before, order.view(), step);
            } catch (IllegalStateException rejected) {
                assertThat(transitionAllowed)
                        .as("transition %s unexpectedly failed from %s/%s: %s",
                                step.action(), before.status(), before.targetStatus(), rejected.getMessage())
                        .isFalse();
                assertSameOrderState(order.view(), before);
            }

            order.validateInvariants();
            assertLifecycleInvariants(order.view());
        }
    }

    @Property(tries = 100)
    void duplicateCreateCommandsAreIdempotent(
            @ForAll @LongRange(min = 1, max = 1_000_000) long quantityLots,
            @ForAll @LongRange(min = 1, max = 10_000_000) long priceTicks
    ) {
        Fixture fixture = new Fixture();
        UUID orderId = UUID.randomUUID();
        OrderCommand command = createCommand(
                UUID.randomUUID(),
                orderId,
                BigDecimal.valueOf(quantityLots, 2),
                BigDecimal.valueOf(priceTicks, 2)
        );

        ProcessingOutcome first = fixture.handler.handle(command);
        ProcessingOutcome duplicate = fixture.handler.handle(command);

        assertThat(duplicate).isEqualTo(first);
        assertThat(fixture.orderWriteCount()).isEqualTo(1);
        assertThat(fixture.eventCount()).isEqualTo(1);
        assertNumericInvariants(fixture.order(orderId).view());
    }

    @Provide
    Arbitrary<BigDecimal> invalidQuantities() {
        Arbitrary<BigDecimal> nonPositive = Arbitraries.longs()
                .between(-1_000_000, 0)
                .map(value -> BigDecimal.valueOf(value, 2));
        Arbitrary<BigDecimal> misaligned = Arbitraries.longs()
                .between(0, 1_000_000)
                .map(cents -> BigDecimal.valueOf(cents * 10 + 1, 3));
        return Arbitraries.oneOf(nonPositive, misaligned);
    }

    @Provide
    Arbitrary<BigDecimal> invalidPrices() {
        Arbitrary<BigDecimal> nonPositive = Arbitraries.longs()
                .between(-10_000_000, 0)
                .map(value -> BigDecimal.valueOf(value, 2));
        Arbitrary<BigDecimal> offTick = Arbitraries.longs()
                .between(0, 10_000_000)
                .map(cents -> BigDecimal.valueOf(cents * 10 + 1, 3));
        return Arbitraries.oneOf(nonPositive, offTick);
    }

    @Provide
    Arbitrary<List<CommandStep>> commandSequences() {
        Arbitrary<CommandStep> steps = Combinators.combine(
                Arbitraries.of(Action.values()),
                Arbitraries.longs().between(1, 1_000_000),
                Arbitraries.longs().between(1, 10_000_000)
        ).as(CommandStep::new);
        return steps.list().ofMinSize(1).ofMaxSize(40);
    }

    @Provide
    Arbitrary<List<LifecycleStep>> lifecycleSequences() {
        Arbitrary<LifecycleStep> steps = Combinators.combine(
                Arbitraries.of(LifecycleAction.values()),
                Arbitraries.longs().between(1, 20_000),
                Arbitraries.longs().between(1, 10_000_000)
        ).as(LifecycleStep::new);
        return steps.list().ofMinSize(1).ofMaxSize(60);
    }

    private static OrderCommand createCommand(
            UUID commandId,
            UUID orderId,
            BigDecimal quantity,
            BigDecimal price
    ) {
        return new OrderCommand(
                SCHEMA_VERSION,
                commandId,
                CommandType.CREATE,
                USER_SUBJECT,
                Instant.EPOCH,
                orderId,
                null,
                listing(),
                OrderSide.BUY,
                OrderType.LIMIT,
                quantity,
                price,
                "DMA",
                "property-test",
                null,
                Map.of()
        );
    }

    private static OrderCommand modifyCommand(
            UUID commandId,
            UUID orderId,
            Long expectedVersion,
            BigDecimal quantity,
            BigDecimal price
    ) {
        return new OrderCommand(
                SCHEMA_VERSION,
                commandId,
                CommandType.MODIFY,
                USER_SUBJECT,
                Instant.EPOCH,
                orderId,
                expectedVersion,
                null,
                null,
                null,
                quantity,
                price,
                null,
                null,
                null,
                Map.of()
        );
    }

    private static OrderCommand cancelCommand(UUID commandId, UUID orderId) {
        return new OrderCommand(
                SCHEMA_VERSION,
                commandId,
                CommandType.CANCEL,
                USER_SUBJECT,
                Instant.EPOCH,
                orderId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of()
        );
    }

    private static ListingSnapshot listing() {
        return new ListingSnapshot(
                1,
                1,
                "AAPL",
                "Apple Inc.",
                "AAPL",
                "XNAS",
                "Nasdaq",
                "US",
                "USD",
                TICK_SIZE,
                SIZE_INCREMENT,
                new BigDecimal("200.00"),
                new BigDecimal("198.00")
        );
    }

    private static TradingOrder aggregate(BigDecimal quantity, BigDecimal price) {
        UUID orderId = UUID.randomUUID();
        TradingOrder order = new TradingOrder(
                orderId,
                USER_SUBJECT,
                "property-test-desk",
                listing(),
                OrderSide.BUY,
                OrderType.LIMIT,
                quantity,
                price,
                "DMA",
                "property-test",
                null,
                orderId,
                "{}"
        );
        setVersion(order, 0);
        return order;
    }

    private static void assertNumericInvariants(OrderView order) {
        assertThat(order.quantity().signum()).isPositive();
        assertThat(order.tradedQuantity().signum()).isGreaterThanOrEqualTo(0);
        assertThat(order.remainingQuantity().signum()).isGreaterThanOrEqualTo(0);
        assertThat(order.tradedQuantity()).isLessThanOrEqualTo(order.quantity());
        assertThat(order.remainingQuantity().add(order.tradedQuantity()))
                .isEqualByComparingTo(order.quantity());
        assertThat(order.quantity().remainder(SIZE_INCREMENT))
                .isEqualByComparingTo(BigDecimal.ZERO);
        if (order.limitPrice() != null) {
            assertThat(order.limitPrice().signum()).isPositive();
            assertThat(order.limitPrice().remainder(TICK_SIZE))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    private static void assertLifecycleInvariants(OrderView order) {
        assertNumericInvariants(order);
        switch (order.status()) {
            case LIVE -> {
                assertThat(order.tradedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(order.remainingQuantity()).isEqualByComparingTo(order.quantity());
                assertThat(order.targetStatus()).isIn(OrderStatus.LIVE, OrderStatus.CANCELLED);
            }
            case PARTIALLY_FILLED -> {
                assertThat(order.tradedQuantity().signum()).isPositive();
                assertThat(order.remainingQuantity().signum()).isPositive();
                assertThat(order.targetStatus()).isIn(OrderStatus.LIVE, OrderStatus.CANCELLED);
            }
            case FILLED -> {
                assertThat(order.tradedQuantity()).isEqualByComparingTo(order.quantity());
                assertThat(order.remainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(order.targetStatus()).isEqualTo(OrderStatus.FILLED);
            }
            case CANCELLED -> {
                assertThat(order.tradedQuantity()).isLessThan(order.quantity());
                assertThat(order.remainingQuantity().signum()).isPositive();
                assertThat(order.targetStatus()).isEqualTo(OrderStatus.CANCELLED);
            }
            case REJECTED -> {
                assertThat(order.tradedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(order.remainingQuantity()).isEqualByComparingTo(order.quantity());
                assertThat(order.targetStatus()).isEqualTo(OrderStatus.REJECTED);
            }
        }
        if (order.tradedQuantity().signum() == 0) {
            assertThat(order.averageTradePrice()).isNull();
        } else {
            assertThat(order.averageTradePrice()).isNotNull().isPositive();
        }
    }

    private static boolean transitionAllowed(OrderView before, LifecycleStep step) {
        boolean active = before.status() == OrderStatus.LIVE
                || before.status() == OrderStatus.PARTIALLY_FILLED;
        BigDecimal quantity = BigDecimal.valueOf(step.quantityLots(), 2);
        return switch (step.action()) {
            case MODIFY -> active
                    && before.targetStatus() != OrderStatus.CANCELLED
                    && quantity.compareTo(before.tradedQuantity()) > 0;
            case FILL -> (active || before.status() == OrderStatus.CANCELLED)
                    && quantity.compareTo(before.remainingQuantity()) <= 0;
            case REQUEST_CANCEL -> active && before.targetStatus() != OrderStatus.CANCELLED;
            case CONFIRM_CANCEL -> active;
        };
    }

    private static void apply(TradingOrder order, LifecycleStep step) {
        switch (step.action()) {
            case MODIFY -> order.modify(
                    BigDecimal.valueOf(step.quantityLots(), 2),
                    BigDecimal.valueOf(step.priceTicks(), 2));
            case FILL -> order.applyFill(
                    BigDecimal.valueOf(step.quantityLots(), 2),
                    BigDecimal.valueOf(step.priceTicks(), 2));
            case REQUEST_CANCEL -> order.requestCancel();
            case CONFIRM_CANCEL -> order.confirmCancel();
        }
    }

    private static void assertTransition(OrderView before, OrderView after, LifecycleStep step) {
        BigDecimal quantity = BigDecimal.valueOf(step.quantityLots(), 2);
        switch (step.action()) {
            case MODIFY -> {
                assertThat(after.quantity()).isEqualByComparingTo(quantity);
                assertThat(after.remainingQuantity())
                        .isEqualByComparingTo(quantity.subtract(before.tradedQuantity()));
                assertThat(after.tradedQuantity()).isEqualByComparingTo(before.tradedQuantity());
                assertThat(after.targetStatus()).isEqualTo(OrderStatus.LIVE);
            }
            case FILL -> {
                assertThat(after.tradedQuantity())
                        .isEqualByComparingTo(before.tradedQuantity().add(quantity));
                assertThat(after.remainingQuantity())
                        .isEqualByComparingTo(before.remainingQuantity().subtract(quantity));
                if (quantity.compareTo(before.remainingQuantity()) == 0) {
                    assertThat(after.status()).isEqualTo(OrderStatus.FILLED);
                    assertThat(after.targetStatus()).isEqualTo(OrderStatus.FILLED);
                } else if (before.status() == OrderStatus.CANCELLED) {
                    assertThat(after.status()).isEqualTo(OrderStatus.CANCELLED);
                    assertThat(after.targetStatus()).isEqualTo(OrderStatus.CANCELLED);
                } else {
                    assertThat(after.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
                    assertThat(after.targetStatus()).isEqualTo(before.targetStatus());
                }
            }
            case REQUEST_CANCEL -> {
                assertThat(after.status()).isEqualTo(before.status());
                assertThat(after.targetStatus()).isEqualTo(OrderStatus.CANCELLED);
            }
            case CONFIRM_CANCEL -> {
                assertThat(after.status()).isEqualTo(OrderStatus.CANCELLED);
                assertThat(after.targetStatus()).isEqualTo(OrderStatus.CANCELLED);
            }
        }
    }

    private static void assertUnchanged(OrderView actual, OrderView expected) {
        assertThat(expected).isNotNull();
        assertThat(actual.status()).isEqualTo(OrderStatus.LIVE);
        assertThat(actual.targetStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertSameOrderState(actual, expected);
    }

    private static void assertSameOrderState(OrderView actual, OrderView expected) {
        assertThat(actual.version()).isEqualTo(expected.version());
        assertThat(actual.status()).isEqualTo(expected.status());
        assertThat(actual.targetStatus()).isEqualTo(expected.targetStatus());
        assertThat(actual.quantity()).isEqualByComparingTo(expected.quantity());
        assertThat(actual.limitPrice()).isEqualByComparingTo(expected.limitPrice());
        assertThat(actual.remainingQuantity()).isEqualByComparingTo(expected.remainingQuantity());
        assertThat(actual.tradedQuantity()).isEqualByComparingTo(expected.tradedQuantity());
        assertThat(actual.averageTradePrice()).isEqualTo(expected.averageTradePrice());
        assertThat(actual.errorMessage()).isEqualTo(expected.errorMessage());
    }

    private static Field field(String name) {
        try {
            Field field = TradingOrder.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void set(Field field, TradingOrder order, Object value) {
        try {
            field.set(order, value);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not prepare the in-memory order projection", exception);
        }
    }

    private static void setVersion(TradingOrder order, long version) {
        set(VERSION_FIELD, order, version);
    }

    private enum Action {
        MODIFY,
        CANCEL
    }

    private enum LifecycleAction {
        MODIFY,
        FILL,
        REQUEST_CANCEL,
        CONFIRM_CANCEL
    }

    private record CommandStep(Action action, long quantityLots, long priceTicks) {
    }

    private record LifecycleStep(LifecycleAction action, long quantityLots, long priceTicks) {
    }

    private static final class Fixture {
        private final Map<UUID, TradingOrder> storedOrders = new HashMap<>();
        private final Map<UUID, ProcessedCommand> processedCommands = new HashMap<>();
        private final Map<UUID, List<OrderEvent>> eventsByCommand = new HashMap<>();
        private int orderWrites;
        private final OrderCommandHandler handler;

        private Fixture() {
            TradingOrderRepository orders = repository(
                    TradingOrderRepository.class,
                    (method, arguments) -> switch (method) {
                        case "existsById" -> storedOrders.containsKey(arguments[0]);
                        case "findByIdAndDeskId" -> {
                            TradingOrder order = storedOrders.get(arguments[0]);
                            String deskId = (String) arguments[1];
                            yield Optional.ofNullable(order)
                                    .filter(candidate -> candidate.getDeskId().equals(deskId));
                        }
                        case "findByParentOrderIdAndStatusIn" -> storedOrders.values().stream()
                                .filter(candidate -> arguments[0].equals(candidate.getParentOrderId()))
                                .filter(candidate -> ((List<?>) arguments[1]).contains(candidate.getStatus()))
                                .toList();
                        case "saveAndFlush" -> {
                            TradingOrder order = (TradingOrder) arguments[0];
                            setVersion(order, order.getVersion() == null ? 0 : order.getVersion() + 1);
                            storedOrders.put(order.getId(), order);
                            orderWrites++;
                            yield order;
                        }
                        default -> throw unsupported(TradingOrderRepository.class, method);
                    }
            );
            ProcessedCommandRepository processed = repository(
                    ProcessedCommandRepository.class,
                    (method, arguments) -> switch (method) {
                        case "findById" -> Optional.ofNullable(processedCommands.get(arguments[0]));
                        case "save" -> {
                            ProcessedCommand entity = (ProcessedCommand) arguments[0];
                            processedCommands.put(entity.result().commandId(), entity);
                            yield entity;
                        }
                        default -> throw unsupported(ProcessedCommandRepository.class, method);
                    }
            );
            OrderEventRepository events = repository(
                    OrderEventRepository.class,
                    (method, arguments) -> switch (method) {
                        case "save" -> {
                            OrderEvent event = (OrderEvent) arguments[0];
                            eventsByCommand.computeIfAbsent(
                                    event.domainEvent().commandId(),
                                    ignored -> new ArrayList<>()
                            ).add(event);
                            yield event;
                        }
                        case "findByCommandIdOrderByOccurredAtAsc" ->
                                List.copyOf(eventsByCommand.getOrDefault(arguments[0], List.of()));
                        default -> throw unsupported(OrderEventRepository.class, method);
                    }
            );

            handler = new OrderCommandHandler(orders, events, processed, new ObjectMapper(),
                    io.micrometer.observation.ObservationRegistry.NOOP);
        }

        private static <T> T repository(Class<T> repositoryType, RepositoryMethod method) {
            Object proxy = Proxy.newProxyInstance(
                    repositoryType.getClassLoader(),
                    new Class<?>[]{repositoryType},
                    (instance, invokedMethod, arguments) -> {
                        if (invokedMethod.getDeclaringClass() == Object.class) {
                            return switch (invokedMethod.getName()) {
                                case "toString" -> repositoryType.getSimpleName() + "PropertyFixture";
                                case "hashCode" -> System.identityHashCode(instance);
                                case "equals" -> instance == arguments[0];
                                default -> throw unsupported(repositoryType, invokedMethod.getName());
                            };
                        }
                        return method.invoke(invokedMethod.getName(), arguments == null ? new Object[0] : arguments);
                    }
            );
            return repositoryType.cast(proxy);
        }

        private static UnsupportedOperationException unsupported(Class<?> type, String method) {
            return new UnsupportedOperationException(type.getSimpleName() + "." + method + " is not used by this fixture");
        }

        private TradingOrder order(UUID orderId) {
            return storedOrders.get(orderId);
        }

        private boolean hasOrder(UUID orderId) {
            return storedOrders.containsKey(orderId);
        }

        private void simulatePartialFill(UUID orderId, BigDecimal tradedQuantity) {
            TradingOrder order = order(orderId);
            set(TRADED_QUANTITY_FIELD, order, tradedQuantity);
            set(REMAINING_QUANTITY_FIELD, order, order.getQuantity().subtract(tradedQuantity));
            set(STATUS_FIELD, order, OrderStatus.PARTIALLY_FILLED);
        }

        private int eventCount() {
            return eventsByCommand.values().stream().mapToInt(List::size).sum();
        }

        private int orderWriteCount() {
            return orderWrites;
        }

        @FunctionalInterface
        private interface RepositoryMethod {
            Object invoke(String method, Object[] arguments);
        }
    }
}

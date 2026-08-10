package com.emporia.execution;

import com.emporia.events.TradingEvents.ExecutionRecoveryView;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.api.dma.DmaCancelOrder;
import exchange.core2.core.common.api.dma.DmaFill;
import exchange.core2.core.common.api.dma.DmaLimitOrder;
import exchange.core2.core.common.api.dma.DmaLifecycleResult;
import exchange.core2.core.common.api.dma.DmaOrderResult;
import exchange.core2.core.common.api.dma.DmaOrderState;
import exchange.core2.core.common.api.dma.DmaProtectedMarketOrder;
import exchange.core2.core.common.api.dma.DmaReplaceOrder;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.simulation.ProductionSimulationResult;
import exchange.core2.core.simulation.SimulationOperation;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class ExchangeCoreExecutionVenueGatewayTest {

    private final RecordingCommands commands = new RecordingCommands();
    private final FakeVenue venue = new FakeVenue();
    private final ExchangeCoreExecutionVenueGateway gateway =
            new ExchangeCoreExecutionVenueGateway(commands, venue);

    @Test
    void usesTheIsoCurrencyCodeSoTheVenueAndPortfolioAgree() {
        venue.submitResponses.add(request -> successful(
                SimulationOperation.SUBMIT_LIMIT, request.deliveryId(),
                new DmaOrderResult(request.orderId(), CommandResultCode.SUCCESS, List.of(), 0, 0),
                DmaOrderState.initial(request)));

        gateway.submit(order(OrderSide.BUY, OrderType.LIMIT, "10", "102.25"));

        // 840 is ISO 4217 for USD, which is the id portfolio-service seeds
        // balances under. Hashing the currency string instead produced
        // 1535516392, leaving the quote-currency account permanently empty and
        // every buy rejected with RISK_NSF.
        CoreSymbolSpecification specification = venue.symbols.getFirst().iterator().next();
        assertThat(specification.quoteCurrency).isEqualTo(840);
    }

    @Test
    void refusesAnUnmappedCurrencyRatherThanHashingIt() {
        ListingSnapshot unmapped = new ListingSnapshot(
                7, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq",
                "US", "XYZ", new BigDecimal("0.01"), BigDecimal.ONE,
                new BigDecimal("101"), new BigDecimal("100"));

        gateway.submit(withListing(order(OrderSide.BUY, OrderType.LIMIT, "10", "102.25"), unmapped));

        // Failing loudly is the point: a silent hash fallback is what made the
        // venue and portfolio-service disagree without anything reporting it.
        assertThat(venue.submits).isEmpty();
        assertThat(commands.rejections).hasSize(1);
    }

    @Test
    void onboardsEachClientExactlyOnceUnderFullEquityRisk() {
        ExchangeCoreExecutionVenueGateway riskGateway =
                ExchangeCoreExecutionVenueGateway.builder(commands, venue)
                        .fullEquityRisk(true)
                        .build();
        for (int i = 0; i < 2; i++) {
            venue.submitResponses.add(request -> successful(
                    SimulationOperation.SUBMIT_LIMIT, request.deliveryId(),
                    new DmaOrderResult(request.orderId(), CommandResultCode.SUCCESS, List.of(), 0, 0),
                    DmaOrderState.initial(request)));
        }

        riskGateway.submit(order(OrderSide.BUY, OrderType.LIMIT, "10", "102.25"));
        riskGateway.submit(order(OrderSide.BUY, OrderType.LIMIT, "10", "102.25"));

        // Both orders belong to the same owner, so the client is imported once
        // and the second submit reuses it.
        assertThat(venue.onboarded).hasSize(1);
        assertThat(venue.submits).hasSize(2);
        assertThat(commands.rejections).isEmpty();
    }

    @Test
    void doesNotOnboardWhenAccountingIsMatchingOnly() {
        venue.submitResponses.add(request -> successful(
                SimulationOperation.SUBMIT_LIMIT, request.deliveryId(),
                new DmaOrderResult(request.orderId(), CommandResultCode.SUCCESS, List.of(), 0, 0),
                DmaOrderState.initial(request)));

        gateway.submit(order(OrderSide.BUY, OrderType.LIMIT, "10", "102.25"));

        // matching-only has no risk profiles, so onboarding must not be attempted.
        assertThat(venue.onboarded).isEmpty();
        assertThat(venue.submits).hasSize(1);
    }

    @Test
    void treatsAnAlreadyKnownClientAsOnboarded() {
        venue.onboardFailure = new IllegalStateException(
                "create portfolio client failed: " + CommandResultCode.USER_MGMT_USER_ALREADY_EXISTS);
        ExchangeCoreExecutionVenueGateway riskGateway =
                new ExchangeCoreExecutionVenueGateway(commands, venue, true);
        venue.submitResponses.add(request -> successful(
                SimulationOperation.SUBMIT_LIMIT, request.deliveryId(),
                new DmaOrderResult(request.orderId(), CommandResultCode.SUCCESS, List.of(), 0, 0),
                DmaOrderState.initial(request)));

        riskGateway.submit(order(OrderSide.BUY, OrderType.LIMIT, "10", "102.25"));

        // This is the restored-snapshot case: exchange-core still holds the
        // profile, so the order must go through rather than be rejected.
        assertThat(venue.submits).hasSize(1);
        assertThat(commands.rejections).isEmpty();
    }

    @Test
    void rejectsAndRetriesWhenOnboardingGenuinelyFails() {
        venue.onboardFailure = new IllegalStateException("portfolio-service unavailable");
        ExchangeCoreExecutionVenueGateway riskGateway =
                new ExchangeCoreExecutionVenueGateway(commands, venue, true);

        riskGateway.submit(order(OrderSide.BUY, OrderType.LIMIT, "10", "102.25"));

        assertThat(venue.submits).isEmpty();
        assertThat(commands.rejections).hasSize(1);

        // The failure must not be cached: a later order retries onboarding
        // rather than inheriting a poisoned future.
        venue.onboardFailure = null;
        venue.submitResponses.add(request -> successful(
                SimulationOperation.SUBMIT_LIMIT, request.deliveryId(),
                new DmaOrderResult(request.orderId(), CommandResultCode.SUCCESS, List.of(), 0, 0),
                DmaOrderState.initial(request)));

        riskGateway.submit(order(OrderSide.BUY, OrderType.LIMIT, "10", "102.25"));

        assertThat(venue.onboarded).hasSize(2);
        assertThat(venue.submits).hasSize(1);
    }

    @Test
    void submitsLimitOrdersWithExactExchangeCoreTicksAndSteps() {
        OrderView order = order(OrderSide.BUY, OrderType.LIMIT, "10", "102.25");
        venue.submitResponses.add(request -> successful(
                SimulationOperation.SUBMIT_LIMIT,
                request.deliveryId(),
                new DmaOrderResult(request.orderId(), CommandResultCode.SUCCESS, List.of(), 0, 0),
                DmaOrderState.initial(request)));

        gateway.submit(order);

        assertThat(venue.symbols).hasSize(1);
        assertThat(venue.submits).hasSize(1);
        DmaLimitOrder request = venue.submits.getFirst();
        assertThat(request.symbol()).isEqualTo(7);
        assertThat(request.side()).isEqualTo(OrderAction.BID);
        assertThat(request.price()).isEqualTo(10_225);
        assertThat(request.quantity()).isEqualTo(10);
        assertThat(commands.rejections).isEmpty();
    }

    @Test
    void capsAProtectedIocAtTheReferencePriceWidenedByTheSlippageBudget() {
        OrderView order = order(OrderSide.BUY, OrderType.MARKET, "10", null);
        venue.protectedResponses.add(request -> successful(
                SimulationOperation.SUBMIT_PROTECTED,
                request.deliveryId(),
                new DmaOrderResult(request.orderId(), CommandResultCode.SUCCESS, List.of(), 0, 0),
                DmaOrderState.initial(request)));

        gateway.submit(order);

        assertThat(venue.submits).isEmpty();
        assertThat(venue.protectedSubmits).hasSize(1);
        DmaProtectedMarketOrder request = venue.protectedSubmits.getFirst();
        assertThat(request.symbol()).isEqualTo(7);
        assertThat(request.side()).isEqualTo(OrderAction.BID);
        // Reference 101.00 widened by 10 bps is 101.101, rounded up a tick to
        // 101.11. It was previously the bare reference, 101.00, which for a buy
        // is a cap the order cannot cross - any upward move left it reporting
        // "no executable liquidity" when the real problem was the cap.
        assertThat(request.protectionPrice()).isEqualTo(10_111);
        assertThat(request.quantity()).isEqualTo(10);
        assertThat(commands.rejections).isEmpty();
    }

    @Test
    void widensASellProtectionDownwardsSoTheBudgetIsNeverTightenedByRounding() {
        OrderView order = order(OrderSide.SELL, OrderType.MARKET, "10", null);
        venue.protectedResponses.add(request -> successful(
                SimulationOperation.SUBMIT_PROTECTED, request.deliveryId(),
                new DmaOrderResult(request.orderId(), CommandResultCode.SUCCESS, List.of(), 0, 0),
                DmaOrderState.initial(request)));

        gateway.submit(order);

        // A seller's budget runs the other way: 101.00 less 10 bps is 100.899,
        // rounded down a tick to 100.89. Rounding always moves away from the
        // anchor, so tick alignment can only widen the budget, never quietly
        // tighten it below what was asked for.
        assertThat(venue.protectedSubmits.getFirst().protectionPrice()).isEqualTo(10_089);
    }

    @Test
    void sweepsRoutedChildrenInsteadOfRestingThem() {
        OrderView child = withParameters(
                order(OrderSide.BUY, OrderType.LIMIT, "10", "102.25"),
                "{\"strategy\":\"SMART\",\"slice\":0,\"tif\":\"IOC\"}");
        venue.protectedResponses.add(request -> successful(
                SimulationOperation.SUBMIT_PROTECTED, request.deliveryId(),
                new DmaOrderResult(request.orderId(), CommandResultCode.SUCCESS, List.of(), 0, 0),
                DmaOrderState.initial(request)));

        gateway.submit(child);

        // A resting child is how thousands of orders accumulated, and the
        // venue's checkpoint cost grows with the book.
        assertThat(venue.submits).isEmpty();
        assertThat(venue.protectedSubmits).hasSize(1);
        // Capped against its own limit price, not the listing reference, so the
        // budget is relative to the price the router actually chose:
        // 102.25 + 10 bps = 102.352, rounded up to 102.36.
        assertThat(venue.protectedSubmits.getFirst().protectionPrice()).isEqualTo(10_236);
    }

    @Test
    void restsAnOrdinaryLimitOrderThatCarriesNoTif() {
        OrderView plain = withParameters(
                order(OrderSide.BUY, OrderType.LIMIT, "10", "102.25"), "{\"strategy\":\"SMART\"}");
        venue.submitResponses.add(request -> successful(
                SimulationOperation.SUBMIT_LIMIT, request.deliveryId(),
                new DmaOrderResult(request.orderId(), CommandResultCode.SUCCESS, List.of(), 0, 0),
                DmaOrderState.initial(request)));

        gateway.submit(plain);

        assertThat(venue.protectedSubmits).isEmpty();
        assertThat(venue.submits).hasSize(1);
    }

    @Test
    void restsRatherThanRejectsWhenExecutionParametersCannotBeRead() {
        OrderView broken = withParameters(
                order(OrderSide.BUY, OrderType.LIMIT, "10", "102.25"), "{not json");
        venue.submitResponses.add(request -> successful(
                SimulationOperation.SUBMIT_LIMIT, request.deliveryId(),
                new DmaOrderResult(request.orderId(), CommandResultCode.SUCCESS, List.of(), 0, 0),
                DmaOrderState.initial(request)));

        gateway.submit(broken);

        // Execution parameters are free-form and may carry anything a client
        // sent. Refusing the order because a field this path does not need
        // failed to parse would turn a cosmetic problem into a rejection.
        assertThat(venue.submits).hasSize(1);
        assertThat(commands.rejections).isEmpty();
    }

    @Test
    void rejectsMarketOrdersWithoutAValidReferencePrice() {
        OrderView order = withListing(order(OrderSide.BUY, OrderType.MARKET, "10", null),
                listing(null));

        gateway.submit(order);

        assertThat(venue.protectedSubmits).isEmpty();
        assertThat(commands.rejections).containsExactly(new Rejection(
                order.id(), order.deskId(), order.listing().exchangeMic(),
                "Exchange-core submit failed: protection price anchor must be positive"));
    }

    @Test
    void publishesTakerAndKnownMakerFills() {
        OrderView ask = order(OrderSide.SELL, OrderType.LIMIT, "10", "102.25");
        OrderView bid = order(OrderSide.BUY, OrderType.LIMIT, "4", "102.25");
        venue.submitResponses.add(request -> successful(
                SimulationOperation.SUBMIT_LIMIT,
                request.deliveryId(),
                new DmaOrderResult(request.orderId(), CommandResultCode.SUCCESS, List.of(), 0, 0),
                DmaOrderState.initial(request)));
        venue.submitResponses.add(request -> {
            DmaFill fill = new DmaFill(
                    venue.submits.getFirst().orderId(),
                    venue.submits.getFirst().clientId(),
                    request.price(),
                    request.quantity(),
                    true,
                    false);
            DmaOrderResult result = new DmaOrderResult(
                    request.orderId(), CommandResultCode.SUCCESS, List.of(fill), 0, 0);
            return successful(
                    SimulationOperation.SUBMIT_LIMIT,
                    request.deliveryId(),
                    result,
                    DmaOrderState.initial(request));
        });

        gateway.submit(ask);
        gateway.submit(bid);

        assertThat(commands.fills).extracting(Fill::orderId).containsExactly(bid.id(), ask.id());
        assertThat(commands.fills).extracting(Fill::quantity).containsExactly(new BigDecimal("4"), new BigDecimal("4"));
        assertThat(commands.fills).extracting(Fill::price).containsExactly(new BigDecimal("102.25"), new BigDecimal("102.25"));
        assertThat(commands.fills).extracting(Fill::venue).containsExactly("XNAS", "XNAS");
    }

    @Test
    void cancelsProtectedIocRemainderAfterPartialFill() {
        OrderView ask = order(OrderSide.SELL, OrderType.LIMIT, "10", "101.00");
        OrderView marketBid = order(OrderSide.BUY, OrderType.MARKET, "12", null);
        venue.submitResponses.add(request -> successful(
                SimulationOperation.SUBMIT_LIMIT,
                request.deliveryId(),
                new DmaOrderResult(request.orderId(), CommandResultCode.SUCCESS, List.of(), 0, 0),
                DmaOrderState.initial(request)));
        venue.protectedResponses.add(request -> {
            DmaFill fill = new DmaFill(
                    venue.submits.getFirst().orderId(),
                    venue.submits.getFirst().clientId(),
                    10_100,
                    10,
                    false,
                    true);
            DmaOrderResult result = new DmaOrderResult(
                    request.orderId(), CommandResultCode.SUCCESS, List.of(fill), 0, 2);
            return successful(
                    SimulationOperation.SUBMIT_PROTECTED,
                    request.deliveryId(),
                    result,
                    DmaOrderState.initial(request));
        });

        gateway.submit(ask);
        gateway.submit(marketBid);

        assertThat(commands.fills).extracting(Fill::orderId).containsExactly(marketBid.id(), ask.id());
        assertThat(commands.cancellations).containsExactly(new Cancellation(
                marketBid.id(), marketBid.deskId(), marketBid.listing().exchangeMic(),
                "Exchange-core confirmed cancellation"));
        assertThat(commands.rejections).isEmpty();
    }

    @Test
    void recoveryUsesRestoredSymbolsWithoutAddingThemAgain() {
        OrderView live = order(OrderSide.SELL, OrderType.LIMIT, "10", "101.00");
        FakeVenue recoveredVenue = new FakeVenue(Set.of(7));
        ExchangeCoreExecutionVenueGateway recoveredGateway =
                new ExchangeCoreExecutionVenueGateway(commands, recoveredVenue);

        recoveredGateway.recover(live);

        assertThat(recoveredVenue.symbols).isEmpty();
    }

    @Test
    void modifiesLimitOrdersWithReplaceOrderRequest() {
        OrderView order = order(OrderSide.BUY, OrderType.LIMIT, "10", "102.25");
        venue.replaceResponses.add(request -> {
            DmaLimitOrder dummy = new DmaLimitOrder(request.deliveryId(), request.orderId(), request.clientId(), request.symbol(), OrderAction.BID, request.newPrice(), request.newQuantity());
            return successful(SimulationOperation.REPLACE, request.deliveryId(),
                    new DmaOrderResult(request.orderId(), CommandResultCode.SUCCESS, List.of(), 0, 0),
                    DmaOrderState.initial(dummy));
        });

        gateway.modify(order);

        assertThat(venue.replaces).hasSize(1);
        DmaReplaceOrder request = venue.replaces.getFirst();
        assertThat(request.newPrice()).isEqualTo(10_225);
    }

    @Test
    void cancelsLimitOrdersWithCancelOrderRequest() {
        OrderView order = order(OrderSide.BUY, OrderType.LIMIT, "10", "102.25");
        venue.cancelResponses.add(request -> {
            DmaLimitOrder dummy = new DmaLimitOrder(request.deliveryId(), request.orderId(), request.clientId(), request.symbol(), OrderAction.BID, 10000, 10);
            return successful(SimulationOperation.CANCEL, request.deliveryId(),
                    new DmaOrderResult(request.orderId(), CommandResultCode.SUCCESS, List.of(), 0, 0),
                    DmaOrderState.initial(dummy));
        });

        gateway.cancel(order);

        assertThat(venue.cancels).hasSize(1);
        assertThat(commands.cancellations).hasSize(1);
    }

    @Test
    void handlesVenueExceptionOnSubmit() {
        OrderView order = order(OrderSide.BUY, OrderType.LIMIT, "10", "102.25");
        venue.submitResponses.add(request -> {
            throw new RuntimeException("Venue submission error");
        });

        gateway.submit(order);

        assertThat(commands.rejections).hasSize(1);
        assertThat(commands.rejections.getFirst().detail()).contains("Venue submission error");
    }

    @Test
    void rejectsModifyForMarketOrder() {
        OrderView order = order(OrderSide.BUY, OrderType.MARKET, "10", null);

        gateway.modify(order);

        assertThat(commands.rejections).hasSize(1);
        assertThat(commands.rejections.getFirst().detail()).contains("only LIMIT orders can be modified");
    }

    @Test
    void rejectsModifyForLimitOrderWithoutPrice() {
        OrderView order = order(OrderSide.BUY, OrderType.LIMIT, "10", null);

        gateway.modify(order);

        assertThat(commands.rejections).hasSize(1);
        assertThat(commands.rejections.getFirst().detail()).contains("LIMIT orders require a limit price");
    }

    @Test
    void handlesVenueExceptionOnCancelAndModify() {
        OrderView order = order(OrderSide.BUY, OrderType.LIMIT, "10", "102.25");
        venue.cancelResponses.add(request -> {
            throw new RuntimeException("Cancel error");
        });
        venue.replaceResponses.add(request -> {
            throw new RuntimeException("Modify error");
        });

        gateway.cancel(order);
        assertThat(commands.rejections).hasSize(1);
        assertThat(commands.rejections.getFirst().detail()).contains("Cancel error");

        gateway.modify(order);
        assertThat(commands.rejections).hasSize(2);
        assertThat(commands.rejections.getLast().detail()).contains("Modify error");
    }

    @Test
    void verifiesExchangeCoreCheckpointException() {
        ExchangeCoreExecutionVenueGateway.ExchangeCoreCheckpointException ex =
                new ExchangeCoreExecutionVenueGateway.ExchangeCoreCheckpointException("Failed to restore", new RuntimeException("IO Error"));

        assertThat(ex.getMessage()).isEqualTo("Failed to restore");
        assertThat(ex.getCause()).hasMessage("IO Error");
    }

    @Test
    void rejectsFullEquityRiskAccountingWithoutPortfolioUrl() {
        assertThatThrownBy(() -> new ExchangeCoreExecutionVenueGateway(
                commands, null, Optional.empty(), null, "ex-1", java.nio.file.Path.of("/tmp"), 1,
                "full-equity-risk", "", Duration.ofSeconds(3), true, 2, 0L,
                new BigDecimal("10"), new SimpleMeterRegistry()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXCHANGE_CORE_PORTFOLIO_URL is required");
    }

    @org.junit.jupiter.api.Disabled("Spins up LMAX Disruptor background threads that prevent Surefire JVM clean shutdown")
    @Test
    void initializesWithRealProductionSimulationVenue(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        ExchangeCoreExecutionVenueGateway realGateway = new ExchangeCoreExecutionVenueGateway(
                commands, null, Optional.empty(), null, "ex-1", tempDir, 1,
                "matching-only", null, Duration.ofSeconds(3), true, 2, 0L,
                new BigDecimal("10"), new SimpleMeterRegistry());

        assertThat(realGateway).isNotNull();
        realGateway.start();
        assertThat(realGateway.isRunning()).isTrue();

        OrderView order = order(OrderSide.BUY, OrderType.LIMIT, "10", "102.25");
        realGateway.submit(order);

        realGateway.stop();
        assertThat(realGateway.isRunning()).isFalse();
    }

    // Note: the per-order checkpoint policy lives inside ProductionSimulationVenue,
    // which FakeVenue replaces wholesale, so it cannot be asserted from here.
    // Its coverage is ProductionSimulationTest in exchange-core, which runs a
    // real engine.

    @Test
    void rebuildsTheVenueLifecycleFromOrderManagementBeforeOpening() {
        OrderView live = order(OrderSide.BUY, OrderType.LIMIT, "10", "102.25");
        TradingDataClient recoverySource = org.mockito.Mockito.mock(TradingDataClient.class);
        when(recoverySource.recoverable())
                .thenReturn(new ExecutionRecoveryView(List.of(live), List.of()));
        ExchangeCoreExecutionVenueGateway recovering =
                ExchangeCoreExecutionVenueGateway.builder(commands, venue)
                        .recoverySource(recoverySource)
                        .build();

        recovering.start();

        // Must be applied before the venue opens: recoverLifecycle replaces the
        // projection, so an order accepted first would be erased by it.
        assertThat(venue.recoveredLifecycles).hasSize(1);
        assertThat(venue.recoveredLifecycles.getFirst().orders())
                .containsKey(ExchangeCoreExecutionVenueGateway.coreOrderId(live));
        assertThat(recovering.isRunning()).isTrue();
    }

    @Test
    void refusesToOpenWhenOrderManagementIsUnreachable() {
        TradingDataClient unreachable = org.mockito.Mockito.mock(TradingDataClient.class);
        when(unreachable.recoverable()).thenThrow(new IllegalStateException("connection refused"));
        ExchangeCoreExecutionVenueGateway recovering =
                new ExchangeCoreExecutionVenueGateway(commands, venue, false, unreachable);

        // Fail closed. Opening with an empty projection would treat redelivered
        // commands as new ones and execute them twice.
        assertThatThrownBy(recovering::start).isInstanceOf(IllegalStateException.class);
        assertThat(recovering.isRunning()).isFalse();
    }

    @Test
    void startsBeforeTheKafkaListenersSoRecoveryPrecedesTheFirstCommand() {
        // Lower phase starts first. Asserted against the real constant so this
        // fails if Spring Kafka's default moves under us.
        assertThat(gateway.getPhase())
                .isLessThan(org.springframework.kafka.listener.AbstractMessageListenerContainer.DEFAULT_PHASE);
    }

    @Test
    void reportsCheckpointFailuresThatDoNotRejectTheOrder() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        FakeVenue failing = new FakeVenue();
        failing.checkpointFailure = new ExchangeCoreExecutionVenueGateway
                .ExchangeCoreCheckpointException(new java.io.IOException("No such file or directory"));
        ExchangeCoreExecutionVenueGateway gateway =
                ExchangeCoreExecutionVenueGateway.builder(commands, failing)
                        .meterRegistry(meters)
                        .build();
        gateway.start();

        gateway.snapshotPeriodically();

        // The venue accepted work it could not persist. That is deliberately
        // not an order rejection - the order did reach the venue - which is
        // exactly why it has to show up somewhere else. It once cost 45 orders
        // their durability with every metric reading healthy.
        assertThat(meters.counter("emporia.execution.venue.checkpoint.failures",
                "operation", "periodic").count()).isEqualTo(1.0);
        assertThat(gateway.health().getStatus().getCode()).isEqualTo("DOWN");
        assertThat(gateway.health().getDetails())
                .containsEntry("checkpointFailuresSinceLastSuccess", 1L);
    }

    @Test
    void returnsToHealthyOnceASnapshotSucceedsAgain() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        FakeVenue recovering = new FakeVenue();
        recovering.checkpointFailure = new ExchangeCoreExecutionVenueGateway
                .ExchangeCoreCheckpointException(new java.io.IOException("No such file or directory"));
        ExchangeCoreExecutionVenueGateway gateway =
                ExchangeCoreExecutionVenueGateway.builder(commands, recovering)
                        .meterRegistry(meters)
                        .build();
        gateway.start();
        gateway.snapshotPeriodically();
        assertThat(gateway.health().getStatus().getCode()).isEqualTo("DOWN");

        // Cleared by a real write, not by an order that may not have
        // checkpointed at all.
        recovering.checkpointFailure = null;
        gateway.snapshotPeriodically();

        assertThat(gateway.health().getStatus().getCode()).isEqualTo("UP");
    }

    @Test
    void healthAndMetricsExposeCheckpointStorageStatus() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        FakeVenue reporting = new FakeVenue();
        reporting.retainedCheckpoints = 2;
        reporting.checkpointStatus = Optional.of(new ExchangeCoreCheckpointStore.StorageStats(
                Path.of("/var/lib/emporia/exchange-core"),
                Optional.of(99L),
                2,
                6,
                4096));
        ExchangeCoreExecutionVenueGateway gateway =
                ExchangeCoreExecutionVenueGateway.builder(commands, reporting)
                        .meterRegistry(meters)
                        .build();

        assertThat(gateway.health().getDetails())
                .containsEntry("checkpointStorageDirectory", "/var/lib/emporia/exchange-core")
                .containsEntry("latestCheckpointId", 99L)
                .containsEntry("checkpointIdCount", 2)
                .containsEntry("checkpointFileCount", 6)
                .containsEntry("checkpointStorageBytes", 4096L)
                .containsEntry("retainedCheckpointsConfigured", 2);
        assertThat(meters.get("emporia.execution.venue.checkpoint.latest.id").gauge().value())
                .isEqualTo(99.0);
        assertThat(meters.get("emporia.execution.venue.checkpoint.ids").gauge().value())
                .isEqualTo(2.0);
        assertThat(meters.get("emporia.execution.venue.checkpoint.files").gauge().value())
                .isEqualTo(6.0);
        assertThat(meters.get("emporia.execution.venue.checkpoint.storage.bytes").gauge().value())
                .isEqualTo(4096.0);
        assertThat(meters.get("emporia.execution.venue.checkpoint.retained.configured").gauge().value())
                .isEqualTo(2.0);
    }

    @Test
    void detectsLocalRunStoragePaths() {
        assertThat(ExchangeCoreExecutionVenueGateway.usesLocalRunStorage(
                Path.of("execution-service/.local-run/exchange-core-simulation"))).isTrue();
        assertThat(ExchangeCoreExecutionVenueGateway.usesLocalRunStorage(
                Path.of("/var/lib/emporia/exchange-core"))).isFalse();
    }

    @Test
    void snapshotsPeriodicallyOnlyWhileRunning() {
        gateway.snapshotPeriodically();
        assertThat(venue.checkpoints).as("must not snapshot before start").isZero();

        gateway.start();
        gateway.snapshotPeriodically();
        gateway.snapshotPeriodically();

        assertThat(venue.checkpoints).isEqualTo(2);
    }

    @Test
    void periodicSnapshotFailureDoesNotPropagate() {
        FakeVenue failing = new FakeVenue();
        failing.checkpointFailure = new IllegalStateException("disk full");
        ExchangeCoreExecutionVenueGateway failingGateway =
                new ExchangeCoreExecutionVenueGateway(commands, failing);
        failingGateway.start();

        // A failed snapshot is a background problem, not an order rejection:
        // the journal still holds every command since the last good snapshot,
        // so recovery stays correct and only replay time grows. Throwing here
        // would kill the scheduler and stop all future snapshots.
        failingGateway.snapshotPeriodically();

        assertThat(failing.checkpoints).isZero();
    }

    @Test
    void stopCheckpointsAndClosesTheVenue() {
        gateway.start();

        gateway.stop();

        assertThat(venue.checkpoints).isEqualTo(1);
        assertThat(venue.closed).isTrue();
    }

    private static ProductionSimulationResult successful(
            SimulationOperation operation,
            long deliveryId,
            DmaOrderResult command,
            DmaOrderState initialState) {
        DmaOrderState state = initialState.applySubmitResult(command);
        return new ProductionSimulationResult(
                operation,
                0,
                1,
                new DmaLifecycleResult(deliveryId, command, state, false));
    }

    private static OrderView order(OrderSide side, OrderType type, String quantity, String limitPrice) {
        return new OrderView(
                UUID.randomUUID(), 2, "trader-a", "desk-a", listing(),
                side, type, new BigDecimal(quantity),
                limitPrice == null ? null : new BigDecimal(limitPrice),
                new BigDecimal(quantity), BigDecimal.ZERO, null,
                OrderStatus.LIVE, OrderStatus.LIVE, "DMA", "test-order",
                null, UUID.randomUUID(), "{}", null,
                Instant.parse("2026-07-26T00:00:00Z"),
                Instant.parse("2026-07-26T00:00:01Z"));
    }

    private static ListingSnapshot listing() {
        return listing(new BigDecimal("101"));
    }

    private static ListingSnapshot listing(BigDecimal referencePrice) {
        return new ListingSnapshot(
                7, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq",
                "US", "USD", new BigDecimal("0.01"), BigDecimal.ONE,
                referencePrice, new BigDecimal("100"));
    }

    private static OrderView withListing(OrderView order, ListingSnapshot listing) {
        return new OrderView(
                order.id(), order.version(), order.ownerSubject(), order.deskId(), listing,
                order.side(), order.type(), order.quantity(), order.limitPrice(),
                order.remainingQuantity(), order.tradedQuantity(), order.averageTradePrice(),
                order.status(), order.targetStatus(), order.destination(), order.originatorReference(),
                order.parentOrderId(), order.rootOrderId(), order.executionParameters(),
                order.errorMessage(), order.createdAt(), order.updatedAt());
    }

    private static OrderView withParameters(OrderView order, String executionParameters) {
        return new OrderView(
                order.id(), order.version(), order.ownerSubject(), order.deskId(), order.listing(),
                order.side(), order.type(), order.quantity(), order.limitPrice(),
                order.remainingQuantity(), order.tradedQuantity(), order.averageTradePrice(),
                order.status(), order.targetStatus(), order.destination(), order.originatorReference(),
                order.parentOrderId(), order.rootOrderId(), executionParameters,
                order.errorMessage(), order.createdAt(), order.updatedAt());
    }

    private static final class FakeVenue implements ExchangeCoreExecutionVenueGateway.ExchangeCoreVenue {
        private final Queue<Function<DmaLimitOrder, ProductionSimulationResult>> submitResponses = new ArrayDeque<>();
        private final Queue<Function<DmaProtectedMarketOrder, ProductionSimulationResult>> protectedResponses =
                new ArrayDeque<>();
        private final ArrayDeque<Collection<CoreSymbolSpecification>> symbols = new ArrayDeque<>();
        private final ArrayDeque<DmaLimitOrder> submits = new ArrayDeque<>();
        private final ArrayDeque<DmaProtectedMarketOrder> protectedSubmits = new ArrayDeque<>();
        private final Set<Integer> restoredSymbols;
        private int checkpoints;
        private boolean closed;
        private RuntimeException checkpointFailure;
        private Optional<ExchangeCoreCheckpointStore.StorageStats> checkpointStatus = Optional.empty();
        private int retainedCheckpoints;

        private FakeVenue() {
            this(Set.of());
        }

        private FakeVenue(Set<Integer> restoredSymbols) {
            this.restoredSymbols = restoredSymbols;
        }

        @Override
        public void addSymbols(Collection<CoreSymbolSpecification> symbols) {
            this.symbols.add(symbols);
        }

        private final ArrayDeque<exchange.core2.core.common.api.dma.DmaLifecycleSnapshot> recoveredLifecycles =
                new ArrayDeque<>();

        @Override
        public void recoverLifecycle(exchange.core2.core.common.api.dma.DmaLifecycleSnapshot lifecycle) {
            recoveredLifecycles.add(lifecycle);
        }

        private final ArrayDeque<Long> onboarded = new ArrayDeque<>();
        private RuntimeException onboardFailure;

        @Override
        public CompletableFuture<Void> onboardPortfolio(long clientId) {
            onboarded.add(clientId);
            if (onboardFailure != null) {
                return CompletableFuture.failedFuture(onboardFailure);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<ProductionSimulationResult> submit(DmaLimitOrder order) {
            submits.add(order);
            return CompletableFuture.completedFuture(submitResponses.remove().apply(order));
        }

        @Override
        public CompletableFuture<ProductionSimulationResult> submitProtected(DmaProtectedMarketOrder order) {
            protectedSubmits.add(order);
            return CompletableFuture.completedFuture(protectedResponses.remove().apply(order));
        }

        private final Queue<Function<DmaReplaceOrder, ProductionSimulationResult>> replaceResponses = new ArrayDeque<>();
        private final Queue<Function<DmaCancelOrder, ProductionSimulationResult>> cancelResponses = new ArrayDeque<>();
        private final ArrayDeque<DmaReplaceOrder> replaces = new ArrayDeque<>();
        private final ArrayDeque<DmaCancelOrder> cancels = new ArrayDeque<>();

        @Override
        public CompletableFuture<ProductionSimulationResult> replace(DmaReplaceOrder replacement) {
            replaces.add(replacement);
            return CompletableFuture.completedFuture(replaceResponses.remove().apply(replacement));
        }

        @Override
        public CompletableFuture<ProductionSimulationResult> cancel(DmaCancelOrder cancellation) {
            cancels.add(cancellation);
            return CompletableFuture.completedFuture(cancelResponses.remove().apply(cancellation));
        }

        @Override
        public Set<Integer> restoredSymbols() {
            return restoredSymbols;
        }

        @Override
        public Optional<ExchangeCoreCheckpointStore.StorageStats> checkpointStatus() {
            return checkpointStatus;
        }

        @Override
        public int retainedCheckpoints() {
            return retainedCheckpoints;
        }

        @Override
        public void checkpoint() {
            if (checkpointFailure != null) {
                throw checkpointFailure;
            }
            checkpoints++;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class RecordingCommands extends ExecutionCommandPublisher {
        private final ArrayDeque<Fill> fills = new ArrayDeque<>();
        private final ArrayDeque<Rejection> rejections = new ArrayDeque<>();
        private final ArrayDeque<Cancellation> cancellations = new ArrayDeque<>();

        private RecordingCommands() {
            super(null, "executions", new SimpleMeterRegistry(), ObservationRegistry.NOOP);
        }

        @Override
        void fill(UUID orderId, String deskId, String reference, BigDecimal quantity, BigDecimal price,
                  String venue, Instant occurredAt) {
            fills.add(new Fill(orderId, quantity, price, venue));
        }

        @Override
        void reject(UUID orderId, String deskId, String reference, String venue, String detail) {
            rejections.add(new Rejection(orderId, deskId, venue, detail));
        }

        @Override
        void venueCancel(UUID orderId, String deskId, String reference, String venue, String detail) {
            cancellations.add(new Cancellation(orderId, deskId, venue, detail));
        }
    }

    private record Fill(UUID orderId, BigDecimal quantity, BigDecimal price, String venue) {
    }

    private record Rejection(UUID orderId, String deskId, String venue, String detail) {
    }

    private record Cancellation(UUID orderId, String deskId, String venue, String detail) {
    }
}

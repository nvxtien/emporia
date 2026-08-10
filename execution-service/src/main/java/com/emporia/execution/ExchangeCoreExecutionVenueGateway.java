package com.emporia.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import com.emporia.events.math.FixedPointMath;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.dma.DmaCancelOrder;
import exchange.core2.core.common.api.dma.DmaLifecycleSnapshot;
import exchange.core2.core.common.api.dma.DmaFill;
import exchange.core2.core.common.api.dma.DmaLimitOrder;
import exchange.core2.core.common.api.dma.DmaOrderResult;
import exchange.core2.core.common.api.dma.DmaOrderStatus;
import exchange.core2.core.common.api.dma.DmaProtectedMarketOrder;
import exchange.core2.core.common.api.dma.DmaReplaceOrder;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.simulation.ProductionSimulation;
import exchange.core2.core.simulation.ProductionSimulationAccounting;
import exchange.core2.core.simulation.ProductionSimulationCheckpoint;
import exchange.core2.core.simulation.ProductionSimulationConfiguration;
import exchange.core2.core.simulation.ProductionSimulationResult;
import exchange.core2.core.simulation.http.EmporiaHttpGatewayConfiguration;
import exchange.core2.core.simulation.http.HttpEmporiaPortfolioGateway;
import exchange.core2.core.simulation.outbox.DurableEmporiaPortfolioGateway;
import exchange.core2.core.simulation.outbox.PortfolioOutboxConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(name = "emporia.execution.venue-mode", havingValue = "exchange-core")
public class ExchangeCoreExecutionVenueGateway implements ExecutionVenueGateway, SmartLifecycle, HealthIndicator {
    private static final Logger log = LoggerFactory.getLogger(ExchangeCoreExecutionVenueGateway.class);
    private static final String ACCOUNTING_FULL_EQUITY = "full-equity-risk";
    /** 10 bps: wide enough to cross a tick or two, tight enough to bound a bad print. */
    static final BigDecimal DEFAULT_SLIPPAGE_BPS = BigDecimal.TEN;
    private static final ObjectMapper PARAMETERS = new ObjectMapper();
    /**
     * ISO 4217 numeric codes, the identity portfolio-service stores balances
     * under. Kept minimal on purpose — an entry is only correct if
     * portfolio-service can actually seed that currency.
     */
    private static final Map<String, Integer> ISO_4217 = Map.of(
            "USD", 840,
            "EUR", 978,
            "GBP", 826,
            "JPY", 392,
            "CHF", 756,
            "CAD", 124,
            "AUD", 36,
            "HKD", 344,
            "SGD", 702,
            "VND", 704);

    private final ExecutionCommandPublisher commands;
    private final ExchangeCoreVenue venue;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Set<Integer> symbols = ConcurrentHashMap.newKeySet();
    private final Map<Long, Correlation> correlations = new ConcurrentHashMap<>();
    /**
     * Onboarding future per client, so a client is imported exactly once.
     *
     * <p>Deliberately a map of futures rather than a {@code Set} guard: two
     * orders from the same user can arrive concurrently, and a check-then-act
     * guard would let the second proceed while the first is still importing —
     * reaching the risk engine before the profile exists, which is the very
     * failure this prevents. Both callers here await the same future.
     */
    private final Map<Long, CompletableFuture<Void>> onboarded = new ConcurrentHashMap<>();
    /** Onboarding only applies under full-equity-risk; matching-only has no risk profiles. */
    private final boolean fullEquityRisk;
    /** Order-management, the source of truth the lifecycle projection is rebuilt from. */
    private final TradingDataClient recoverySource;
    private final ExchangeCoreLifecycleRebuilder lifecycleRebuilder;
    /** How far past its own price a sweep may execute, in basis points. */
    private final BigDecimal slippageBps;
    private final MeterRegistry meters;
    /** Checkpoint failures since the last successful snapshot; 0 means healthy. */
    private final AtomicLong checkpointFailures = new AtomicLong();
    private volatile Instant lastCheckpointSuccess;
    private volatile Instant lastCheckpointFailure;
    private volatile String lastCheckpointFailureDetail;

    @Autowired
    public ExchangeCoreExecutionVenueGateway(
            ExecutionCommandPublisher commands,
            ServiceAccessTokenProvider tokenProvider,
            Optional<DataSource> dataSource,
            TradingDataClient recoverySource,
            @Value("${emporia.execution.exchange-core.exchange-id}") String exchangeId,
            @Value("${emporia.execution.exchange-core.storage-directory}") Path storage,
            @Value("${emporia.execution.exchange-core.symbol-partitions}") int partitions,
            @Value("${emporia.execution.exchange-core.accounting-mode:matching-only}") String accountingMode,
            @Value("${emporia.execution.exchange-core.portfolio-url:}") String portfolioUrl,
            @Value("${emporia.execution.exchange-core.portfolio-request-timeout:3s}") Duration portfolioTimeout,
            // Defaults off for conservative local/prod parity. When enabled,
            // the journal restores the engine and startup rebuilds the DMA
            // lifecycle from order-management; scripts/perf/crash-recovery-check.sh
            // is the acceptance gate before relying on it in production.
            @Value("${emporia.execution.exchange-core.journaling:false}") boolean journaling,
            @Value("${emporia.execution.exchange-core.retained-checkpoints:2}") int retainedCheckpoints,
            @Value("${emporia.execution.exchange-core.min-free-storage-bytes:0}") long minFreeStorageBytes,
            @Value("${emporia.execution.sor.slippage-bps:10}") BigDecimal slippageBps,
            MeterRegistry meters,
            Environment environment)
            throws IOException {
        this(commands, tokenProvider, dataSource, recoverySource, exchangeId, storage, partitions,
                accountingMode, portfolioUrl, portfolioTimeout, journaling, retainedCheckpoints,
                minFreeStorageBytes, slippageBps, meters, activeProfiles(environment));
    }

    public ExchangeCoreExecutionVenueGateway(
            ExecutionCommandPublisher commands,
            ServiceAccessTokenProvider tokenProvider,
            Optional<DataSource> dataSource,
            TradingDataClient recoverySource,
            String exchangeId,
            Path storage,
            int partitions,
            String accountingMode,
            String portfolioUrl,
            Duration portfolioTimeout,
            boolean journaling,
            int retainedCheckpoints,
            long minFreeStorageBytes,
            BigDecimal slippageBps,
            MeterRegistry meters)
            throws IOException {
        this(commands, tokenProvider, dataSource, recoverySource, exchangeId, storage, partitions,
                accountingMode, portfolioUrl, portfolioTimeout, journaling, retainedCheckpoints,
                minFreeStorageBytes, slippageBps, meters, Set.of());
    }

    private ExchangeCoreExecutionVenueGateway(
            ExecutionCommandPublisher commands,
            ServiceAccessTokenProvider tokenProvider,
            Optional<DataSource> dataSource,
            TradingDataClient recoverySource,
            String exchangeId,
            Path storage,
            int partitions,
            String accountingMode,
            String portfolioUrl,
            Duration portfolioTimeout,
            boolean journaling,
            int retainedCheckpoints,
            long minFreeStorageBytes,
            BigDecimal slippageBps,
            MeterRegistry meters,
            Set<String> activeProfiles)
            throws IOException {
        this(GatewaySpec.builder(commands, buildVenue(ProductionVenueSpec.builder()
                        .exchangeId(exchangeId)
                        .storage(storage)
                        .partitions(partitions)
                        .accounting(buildAccounting(accountingMode, exchangeId, portfolioUrl,
                                portfolioTimeout, tokenProvider, dataSource.orElse(null)))
                        .journaling(journaling)
                        .retainedCheckpoints(retainedCheckpoints)
                        .minFreeStorageBytes(minFreeStorageBytes)
                        .activeProfiles(activeProfiles)
                        .build()))
                .fullEquityRisk(ACCOUNTING_FULL_EQUITY.equalsIgnoreCase(accountingMode))
                .recoverySource(recoverySource)
                .slippageBps(slippageBps)
                .meterRegistry(meters)
                .buildSpec());
        log.info("Exchange-core venue started with accounting-mode={} journaling={} retained-checkpoints={} "
                        + "min-free-storage-bytes={} slippage-bps={}",
                accountingMode, journaling, retainedCheckpoints, minFreeStorageBytes, slippageBps);
    }

    private static ProductionSimulationAccounting buildAccounting(
            String accountingMode, String exchangeId, String portfolioUrl,
            Duration portfolioTimeout, ServiceAccessTokenProvider tokenProvider,
            DataSource dataSource) {
        if (ACCOUNTING_FULL_EQUITY.equalsIgnoreCase(accountingMode)) {
            if (portfolioUrl == null || portfolioUrl.isBlank()) {
                throw new IllegalArgumentException(
                        "EXCHANGE_CORE_PORTFOLIO_URL is required when accounting-mode is full-equity-risk");
            }
            EmporiaHttpGatewayConfiguration gatewayConfig = new EmporiaHttpGatewayConfiguration(
                    URI.create(portfolioUrl),
                    exchangeId,
                    portfolioTimeout,
                    Map.of("Authorization", tokenProvider.authorization()));
            // Use a token-refreshing gateway so each HTTP call picks up a fresh token.
            exchange.core2.core.simulation.http.HttpEmporiaPortfolioGateway httpGateway = new exchange.core2.core.simulation.http.HttpEmporiaPortfolioGateway(gatewayConfig) {
                @Override
                public java.util.concurrent.CompletableFuture<exchange.core2.core.simulation.EmporiaPortfolioSeed> load(long clientId) {
                    return refreshedGateway(exchangeId, portfolioUrl, portfolioTimeout, tokenProvider).load(clientId);
                }

                @Override
                public java.util.concurrent.CompletableFuture<Void> publish(
                        exchange.core2.core.simulation.EmporiaPortfolioSnapshot snapshot) {
                    return refreshedGateway(exchangeId, portfolioUrl, portfolioTimeout, tokenProvider).publish(snapshot);
                }
            };
            
            if (dataSource != null) {
                log.info("Exchange-core full-equity-risk portfolio gateway → {} (Durable Outbox Enabled)", portfolioUrl);
                DurableEmporiaPortfolioGateway durableGateway = DurableEmporiaPortfolioGateway.start(
                        httpGateway, dataSource, PortfolioOutboxConfiguration.defaults(exchangeId));
                return ProductionSimulationAccounting.fullEquityRisk(durableGateway);
            } else {
                log.info("Exchange-core full-equity-risk portfolio gateway → {}", portfolioUrl);
                return ProductionSimulationAccounting.fullEquityRisk(httpGateway);
            }
        }
        return ProductionSimulationAccounting.matchingOnly();
    }

    /**
     * Builds a one-shot {@link HttpEmporiaPortfolioGateway} with a freshly
     * obtained bearer token so each request uses a valid, non-expired credential.
     */
    private static HttpEmporiaPortfolioGateway refreshedGateway(
            String exchangeId, String portfolioUrl, Duration timeout,
            ServiceAccessTokenProvider tokenProvider) {
        EmporiaHttpGatewayConfiguration config = new EmporiaHttpGatewayConfiguration(
                URI.create(portfolioUrl),
                exchangeId,
                timeout,
                Map.of("Authorization", tokenProvider.authorization()));
        return new HttpEmporiaPortfolioGateway(config);
    }

    private static ExchangeCoreVenue buildVenue(ProductionVenueSpec spec) throws IOException {
        validateProductionGuardrails(spec.storage(), spec.minFreeStorageBytes(), spec.activeProfiles());
        // exchange-core writes its snapshots straight into this directory and does
        // not create it. Only ExchangeCoreCheckpointStore.save did, and that runs
        // after the snapshot write has already failed.
        //
        // The failure mode is quiet and expensive: without the directory every
        // order reaches the venue and then fails to checkpoint, while the submit
        // still returns 201 because the order genuinely arrived. The default
        // storage location now lives under .local-run rather than target/ so a
        // build can no longer delete it mid-run, and a checkpoint that fails
        // anyway is counted and turns the venue's health DOWN.
        Files.createDirectories(spec.storage());
        if (usesLocalRunStorage(spec.storage())) {
            log.warn("Exchange-core storage directory {} is under .local-run. "
                    + "That is appropriate for local development, but production internal matching "
                    + "must use an explicit persistent volume.", spec.storage().toAbsolutePath().normalize());
        }
        return new ProductionSimulationVenue(spec);
    }

    private static Set<String> activeProfiles(Environment environment) {
        if (environment == null) {
            return Set.of();
        }
        return Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
    }

    static GatewayBuilder builder(ExecutionCommandPublisher commands, ExchangeCoreVenue venue) {
        return GatewaySpec.builder(commands, venue);
    }

    /** Matching-only wiring; no client onboarding, since there are no risk profiles. */
    public ExchangeCoreExecutionVenueGateway(ExecutionCommandPublisher commands, ExchangeCoreVenue venue) {
        this(GatewaySpec.builder(commands, venue).buildSpec());
    }

    public ExchangeCoreExecutionVenueGateway(ExecutionCommandPublisher commands, ExchangeCoreVenue venue,
                                             boolean fullEquityRisk) {
        this(GatewaySpec.builder(commands, venue)
                .fullEquityRisk(fullEquityRisk)
                .buildSpec());
    }

    /**
     * @param recoverySource order-management, from which the venue's lifecycle
     *                       projection is rebuilt at startup. {@code null}
     *                       skips the rebuild, which is only appropriate for
     *                       tests that construct the venue directly.
     */
    public ExchangeCoreExecutionVenueGateway(ExecutionCommandPublisher commands, ExchangeCoreVenue venue,
                                             boolean fullEquityRisk, TradingDataClient recoverySource) {
        this(GatewaySpec.builder(commands, venue)
                .fullEquityRisk(fullEquityRisk)
                .recoverySource(recoverySource)
                .buildSpec());
    }

    public ExchangeCoreExecutionVenueGateway(ExecutionCommandPublisher commands, ExchangeCoreVenue venue,
                                             boolean fullEquityRisk, TradingDataClient recoverySource,
                                             BigDecimal slippageBps) {
        this(GatewaySpec.builder(commands, venue)
                .fullEquityRisk(fullEquityRisk)
                .recoverySource(recoverySource)
                .slippageBps(slippageBps)
                .buildSpec());
    }

    public ExchangeCoreExecutionVenueGateway(ExecutionCommandPublisher commands, ExchangeCoreVenue venue,
                                             boolean fullEquityRisk, TradingDataClient recoverySource,
                                             BigDecimal slippageBps, MeterRegistry meters) {
        this(GatewaySpec.builder(commands, venue)
                .fullEquityRisk(fullEquityRisk)
                .recoverySource(recoverySource)
                .slippageBps(slippageBps)
                .meterRegistry(meters)
                .buildSpec());
    }

    private ExchangeCoreExecutionVenueGateway(GatewaySpec spec) {
        this.commands = spec.commands();
        this.venue = spec.venue();
        this.fullEquityRisk = spec.fullEquityRisk();
        this.recoverySource = spec.recoverySource();
        this.slippageBps = spec.slippageBps();
        this.meters = spec.meters();
        if (spec.slippageBps().signum() < 0) {
            throw new IllegalArgumentException("slippage budget must not be negative");
        }
        // Shares the gateway's budget so a rebuilt projection carries the same
        // protection price the submit path would have sent.
        this.lifecycleRebuilder = new ExchangeCoreLifecycleRebuilder(spec.slippageBps());
        this.symbols.addAll(spec.venue().restoredSymbols());
        registerCheckpointGauges();
    }

    /**
     * Imports a client into the risk engine before their first order, exactly once.
     *
     * <p>Mirrors {@link #ensureSymbol}, with one difference that matters: this
     * blocks on the import. The risk engine must hold the profile before the
     * order reaches it, so completing asynchronously would only move the race
     * rather than remove it.
     *
     * <p>Failures are raised rather than swallowed. A client that cannot be
     * onboarded produces the same bare REJECTED as a genuine risk rejection,
     * and telling those two apart is what made this failure take three separate
     * investigations to find.
     */
    private void ensureClient(long clientId) {
        if (!fullEquityRisk) return;
        try {
            onboarded.computeIfAbsent(clientId, venue::onboardPortfolio).join();
        } catch (RuntimeException failure) {
            // A client the risk engine already knows is the state we want, so this
            // is success, not an error. It happens whenever the venue restored its
            // state from a snapshot: exchange-core still holds the profile while
            // this map starts empty, and importPortfolio then fails on ApiAddUser
            // with USER_MGMT_USER_ALREADY_EXISTS rather than double-funding.
            if (alreadyOnboarded(failure)) {
                onboarded.put(clientId, CompletableFuture.completedFuture(null));
                return;
            }
            // Do not cache a genuine failure: the next order should retry rather
            // than inherit a permanently poisoned future.
            onboarded.remove(clientId);
            throw new IllegalStateException(
                    "Could not onboard client " + clientId + " into the exchange-core risk engine; "
                            + "orders cannot be risk-checked until portfolio-service supplies a seed", failure);
        }
    }

    /**
     * Matches on the {@code CommandResultCode} name because that is the only
     * signal that survives the venue boundary: onboarding returns
     * {@code CompletableFuture<Void>}, and exchange-core reports the code inside
     * an {@link IllegalStateException} message.
     */
    private static boolean alreadyOnboarded(Throwable failure) {
        Throwable cause = failure;
        // Bounded rather than walking to null: a self-referencing cause would
        // otherwise loop forever, and no real chain here is this deep.
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains(CommandResultCode.USER_MGMT_USER_ALREADY_EXISTS.name())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void submit(OrderView order) {
        try {
            remember(order);
            ensureSymbol(order.listing());
            ensureClient(clientId(order));
            // A routed child sweeps rather than rests: it takes what liquidity
            // is there within its slippage budget and cancels the remainder,
            // instead of joining the book and waiting. Resting children are how
            // the book grew to thousands of orders, and the checkpoint cost
            // grows with it.
            if (order.type() == OrderType.MARKET || sweeps(order)) {
                DmaProtectedMarketOrder request = new DmaProtectedMarketOrder(
                        deliveryId(order, "submit-protected"),
                        coreOrderId(order),
                        clientId(order),
                        symbolId(order.listing()),
                        side(order.side()),
                        protectionPriceTicks(order, slippageBps),
                        quantitySteps(order.quantity(), order.listing()));
                handleProtected(order, venue.submitProtected(request).join());
            } else {
                DmaLimitOrder request = new DmaLimitOrder(
                        deliveryId(order, "submit"),
                        coreOrderId(order),
                        clientId(order),
                        symbolId(order.listing()),
                        side(order.side()),
                        priceTicks(order),
                        quantitySteps(order.quantity(), order.listing()));
                handle(order, "SUBMIT", venue.submit(request).join());
            }
        } catch (RuntimeException failure) {
            if (checkpointFailed(order, "submit", failure)) return;
            reject(order, "Exchange-core submit failed: " + failure.getMessage());
        }
    }

    @Override
    public void modify(OrderView order) {
        try {
            requireLimit(order);
            remember(order);
            ensureSymbol(order.listing());
            DmaReplaceOrder request = new DmaReplaceOrder(
                    deliveryId(order, "replace"),
                    coreOrderId(order),
                    clientId(order),
                    symbolId(order.listing()),
                    side(order.side()),
                    priceTicks(order),
                    quantitySteps(order.quantity(), order.listing()));
            handle(order, "REPLACE", venue.replace(request).join());
        } catch (RuntimeException failure) {
            if (checkpointFailed(order, "replace", failure)) return;
            reject(order, "Exchange-core replace failed: " + failure.getMessage());
        }
    }

    @Override
    public void cancel(OrderView order) {
        try {
            remember(order);
            ensureSymbol(order.listing());
            DmaCancelOrder request = new DmaCancelOrder(
                    deliveryId(order, "cancel"),
                    coreOrderId(order),
                    clientId(order),
                    symbolId(order.listing()));
            ProductionSimulationResult result = venue.cancel(request).join();
            publishCancel(order, result);
        } catch (RuntimeException failure) {
            if (checkpointFailed(order, "cancel", failure)) return;
            reject(order, "Exchange-core cancel failed: " + failure.getMessage());
        }
    }

    @Override
    public void recover(OrderView order) {
        remember(order);
        ensureSymbol(order.listing());
    }

    /**
     * Rebuilds the venue's lifecycle projection, then opens for trading.
     *
     * <p>The rebuild must happen here rather than in
     * {@code ExecutionEventConsumer.recover()}, which runs a second after the
     * application is ready: by then the Kafka listeners are consuming, and
     * applying a rebuilt projection <em>replaces</em> whatever the lifecycle
     * holds, so any order accepted in that window would be silently erased.
     *
     * <p>Fails closed. If order-management cannot be reached there is no way to
     * tell which orders the venue is already responsible for, and opening with
     * an empty projection would accept redelivered commands as new ones and
     * execute them twice. Refusing to start is the safe direction, and matches
     * how full-equity-risk already refuses to start without a portfolio URL.
     */
    @Override
    public void start() {
        if (recoverySource != null) {
            venue.recoverLifecycle(
                    lifecycleRebuilder.rebuild(recoverySource.recoverable().directOrders()));
        }
        running.set(true);
    }

    /**
     * Starts before the Kafka listener containers, so the lifecycle projection
     * is in place before the first command is consumed.
     *
     * <p>Stated explicitly because both this bean and the listener containers
     * would otherwise sit at the default phase, where the ordering that makes
     * recovery correct is incidental rather than guaranteed.
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1024;
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            try {
                venue.checkpoint();
            } catch (RuntimeException checkpointFailure) {
                log.warn("Exchange-core checkpoint on shutdown failed: {}",
                        checkpointFailure.getMessage());
            } finally {
                venue.close();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Order IDs the matching engine currently has resting for a client - see
     * {@link ExchangeCoreVenue#openOrderIds}. Exposed for reconciliation
     * against order-management's own record of live orders.
     */
    public CompletableFuture<Set<Long>> openOrderIds(long clientId) {
        return venue.openOrderIds(clientId);
    }

    /**
     * Snapshots engine state on a timer rather than per order.
     *
     * <p>The journal alone is enough to recover, but replay time grows without
     * bound, so a periodic snapshot bounds it: recovery loads the newest
     * snapshot and replays only the commands journalled after it.
     *
     * <p>A failure here is a background problem, not an order rejection - which
     * is the meaning change from the per-order checkpoint, where a snapshot
     * failure rejected the order that triggered it. Nothing is lost when this
     * fails: the journal still holds every command since the last successful
     * snapshot, so recovery stays correct and only takes longer.
     *
     * <p>{@code checkpoint()} takes the engine's write lock, so this briefly
     * blocks order submission. That is the cost of snapshotting at all, now paid
     * once a minute instead of once an order.
     */
    @Scheduled(
            initialDelayString = "${emporia.execution.exchange-core.snapshot-interval:60s}",
            fixedDelayString = "${emporia.execution.exchange-core.snapshot-interval:60s}")
    void snapshotPeriodically() {
        if (!running.get()) return;
        try {
            venue.checkpoint();
            recordCheckpointSuccess();
        } catch (RuntimeException snapshotFailure) {
            log.error("Periodic exchange-core snapshot failed. Recovery is still correct - "
                    + "the journal holds every command since the last successful snapshot - "
                    + "but replay will take longer until one succeeds", snapshotFailure);
            recordCheckpointFailure("periodic", unwrap(snapshotFailure));
        }
    }

    private void handle(OrderView order, String operation, ProductionSimulationResult result) {
        DmaOrderResult command = result.lifecycleResult().commandResult();
        publishFills(order, operation, result);
        DmaOrderStatus status = result.lifecycleResult().orderState().status();
        if (command.resultCode() != CommandResultCode.SUCCESS || status == DmaOrderStatus.REJECTED) {
            reject(order, "Exchange-core " + operation + " rejected: " + command.resultCode());
            return;
        }
        if (status == DmaOrderStatus.CANCELLED) {
            publishCancel(order, result);
        }
    }

    private void handleProtected(OrderView order, ProductionSimulationResult result) {
        DmaOrderResult command = result.lifecycleResult().commandResult();
        publishFills(order, "SUBMIT-PROTECTED", result);
        if (command.resultCode() != CommandResultCode.SUCCESS) {
            reject(order, "Exchange-core protected market IOC rejected: " + command.resultCode());
            return;
        }
        if (command.rejectedQuantity() > 0) {
            if (command.fills().isEmpty()) {
                reject(order, "Exchange-core protected market IOC found no executable liquidity");
            } else {
                publishCancel(order, result);
            }
        }
    }

    private void publishFills(OrderView taker, String operation, ProductionSimulationResult result) {
        DmaOrderResult command = result.lifecycleResult().commandResult();
        int index = 0;
        for (DmaFill fill : command.fills()) {
            publishFill(taker, operation + "-TAKER-" + index, fill.quantity(), fill.price());
            Correlation maker = correlations.get(fill.makerOrderId());
            if (maker != null) {
                publishFill(maker.order(), operation + "-MAKER-" + index, fill.quantity(), fill.price());
            }
            index++;
        }
    }

    private void publishFill(OrderView order, String label, long quantitySteps, long priceTicks) {
        commands.fill(
                order.id(),
                order.deskId(),
                reference(order, label),
                decimalQuantity(quantitySteps, order.listing()),
                decimalPrice(priceTicks, order.listing()),
                order.listing().exchangeMic(),
                Instant.now());
    }

    private void publishCancel(OrderView order, ProductionSimulationResult result) {
        commands.venueCancel(
                order.id(),
                order.deskId(),
                reference(order, "CANCEL-" + result.partitionSequence()),
                order.listing().exchangeMic(),
                "Exchange-core confirmed cancellation");
    }

    private void reject(OrderView order, String detail) {
        commands.reject(
                order.id(),
                order.deskId(),
                reference(order, "REJECT"),
                order.listing().exchangeMic(),
                detail);
    }

    /**
     * Whether this order sweeps and cancels its remainder rather than resting.
     *
     * <p>Signalled by {@code "tif":"IOC"} in the order's execution parameters,
     * which is how a strategy marks the children it routes.
     *
     * <p>Unreadable parameters mean "rests", not an error. They are free-form
     * and may carry anything a client sent; refusing the order because a field
     * this method does not need failed to parse would turn a cosmetic problem
     * into a rejection.
     */
    static boolean sweeps(OrderView order) {
        String parameters = order.executionParameters();
        if (parameters == null || parameters.isBlank()) return false;
        try {
            JsonNode tif = PARAMETERS.readTree(parameters).get("tif");
            return tif != null && "IOC".equalsIgnoreCase(tif.asText());
        } catch (JsonProcessingException unreadable) {
            log.debug("Ignoring unreadable execution parameters on order {}", order.id());
            return false;
        }
    }

    private boolean checkpointFailed(OrderView order, String operation, RuntimeException failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof ExchangeCoreCheckpointException) {
            log.error("Exchange-core {} for order {} reached the venue, but checkpointing failed",
                    operation, order.id(), cause);
            recordCheckpointFailure(operation, cause);
            return true;
        }
        return false;
    }

    /**
     * Records that the venue accepted an operation it could not persist.
     *
     * <p>This is deliberately not an order rejection - the order did reach the
     * venue, so rejecting it would be a lie - but that makes the failure
     * invisible from outside: the caller sees 201, the venue operation records
     * outcome=success, and nothing counts a rejection. A run once lost
     * durability for 45 consecutive orders with every metric reading healthy
     * and only a log line to show for it.
     *
     * <p>So it is counted and surfaced in health. Losing the ability to persist
     * is a state the venue should be loud about, not one that has to be found
     * by reading logs.
     */
    private void recordCheckpointFailure(String operation, Throwable cause) {
        meters.counter("emporia.execution.venue.checkpoint.failures", "operation", operation)
                .increment();
        checkpointFailures.incrementAndGet();
        lastCheckpointFailure = Instant.now();
        lastCheckpointFailureDetail = cause.getMessage();
    }

    private void recordCheckpointSuccess() {
        checkpointFailures.set(0);
        lastCheckpointSuccess = Instant.now();
    }

    private void registerCheckpointGauges() {
        meters.gauge("emporia.execution.venue.checkpoint.age.seconds", this,
                ExchangeCoreExecutionVenueGateway::checkpointAgeSeconds);
        meters.gauge("emporia.execution.venue.checkpoint.latest.id", this,
                gateway -> gateway.checkpointStatus()
                        .map(ExchangeCoreCheckpointStore.StorageStats::latestCheckpointIdOrZero)
                        .orElse(0L));
        meters.gauge("emporia.execution.venue.checkpoint.ids", this,
                gateway -> gateway.checkpointStatus()
                        .map(ExchangeCoreCheckpointStore.StorageStats::checkpointIdCount)
                        .orElse(0));
        meters.gauge("emporia.execution.venue.checkpoint.files", this,
                gateway -> gateway.checkpointStatus()
                        .map(ExchangeCoreCheckpointStore.StorageStats::checkpointFileCount)
                        .orElse(0));
        meters.gauge("emporia.execution.venue.checkpoint.partial.files", this,
                gateway -> gateway.checkpointStatus()
                        .map(ExchangeCoreCheckpointStore.StorageStats::partialCheckpointFileCount)
                        .orElse(0));
        meters.gauge("emporia.execution.venue.checkpoint.storage.bytes", this,
                gateway -> gateway.checkpointStatus()
                        .map(ExchangeCoreCheckpointStore.StorageStats::storageBytes)
                        .orElse(0L));
        meters.gauge("emporia.execution.venue.checkpoint.storage.usable.bytes", this,
                gateway -> gateway.checkpointStatus()
                        .map(ExchangeCoreCheckpointStore.StorageStats::usableStorageBytes)
                        .orElse(0L));
        meters.gauge("emporia.execution.venue.checkpoint.retained.configured", this,
                gateway -> gateway.venue.retainedCheckpoints());
    }

    /**
     * Reports the venue unhealthy while it cannot persist its state.
     *
     * <p>Cleared by the next successful periodic snapshot rather than by a
     * successful order, so recovery is confirmed by an actual write rather than
     * inferred from an operation that may not have checkpointed at all.
     */
    @Override
    public Health health() {
        long failures = checkpointFailures.get();
        Optional<ExchangeCoreCheckpointStore.StorageStats> checkpoint = checkpointStatus();
        boolean checkpointStatusMissing = checkpoint.isEmpty() && venue.retainedCheckpoints() > 0;
        Health.Builder status = failures == 0 && !checkpointStatusMissing ? Health.up() : Health.down();
        status.withDetail("venue", "exchange-core")
                .withDetail("checkpointFailuresSinceLastSuccess", failures)
                .withDetail("checkpointStatusAvailable", checkpoint.isPresent())
                .withDetail("checkpointAgeSeconds", checkpointAgeSeconds())
                .withDetail("retainedCheckpointsConfigured", venue.retainedCheckpoints());
        checkpoint.ifPresent(stats -> status
                .withDetail("checkpointStorageDirectory", stats.directory().toString())
                .withDetail("latestCheckpointId", stats.latestCheckpointIdOrZero())
                .withDetail("checkpointIdCount", stats.checkpointIdCount())
                .withDetail("checkpointFileCount", stats.checkpointFileCount())
                .withDetail("partialCheckpointFileCount", stats.partialCheckpointFileCount())
                .withDetail("checkpointStorageBytes", stats.storageBytes())
                .withDetail("checkpointUsableStorageBytes", stats.usableStorageBytes()));
        if (checkpointStatusMissing) {
            status.withDetail("checkpointStatusDetail", "unavailable");
        }
        if (lastCheckpointSuccess != null) {
            status.withDetail("lastCheckpointSuccessAt", lastCheckpointSuccess.toString());
        }
        if (lastCheckpointFailure != null) {
            status.withDetail("lastCheckpointFailureAt", lastCheckpointFailure.toString())
                  .withDetail("lastCheckpointFailureDetail",
                            lastCheckpointFailureDetail == null ? "unknown" : lastCheckpointFailureDetail);
        }
        return status.build();
    }

    private long checkpointAgeSeconds() {
        Instant checkpoint = lastCheckpointSuccess;
        if (checkpoint == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(checkpoint, Instant.now()).toSeconds());
    }

    private Optional<ExchangeCoreCheckpointStore.StorageStats> checkpointStatus() {
        try {
            return venue.checkpointStatus();
        } catch (RuntimeException unavailable) {
            log.debug("Exchange-core checkpoint status unavailable", unavailable);
            return Optional.empty();
        }
    }

    private void ensureSymbol(ListingSnapshot listing) {
        int symbol = symbolId(listing);
        if (symbols.add(symbol)) {
            venue.addSymbols(Set.of(CoreSymbolSpecification.builder()
                                                           .symbolId(symbol)
                                                           .type(SymbolType.EQUITY)
                                                           .baseCurrency(stableInt("asset:" + listing.marketSymbol()))
                                                           .quoteCurrency(isoCurrencyCode(listing.currency()))
                                                           .baseScaleK(1)
                                                           .quoteScaleK(1)
                                                           .takerFee(0)
                                                           .makerFee(0)
                                                           .build()));
        }
    }

    private void remember(OrderView order) {
        correlations.put(coreOrderId(order), new Correlation(order));
    }

    private static void requireLimit(OrderView order) {
        if (order.type() != OrderType.LIMIT) {
            throw new IllegalArgumentException("only LIMIT orders can be modified in exchange-core mode");
        }
        if (order.limitPrice() == null) {
            throw new IllegalArgumentException("LIMIT orders require a limit price");
        }
    }

    static long priceTicks(OrderView order) {
        return FixedPointMath.exactUnits(order.limitPrice(), order.listing().tickSize(), "limit price");
    }

    /**
     * The worst price a protected IOC may execute at: the order's own price
     * widened by the slippage budget, in the direction that lets it cross.
     *
     * <p>This previously returned the listing's bare reference price, with no
     * tolerance and no regard for side. For a buy that put the cap exactly at
     * reference, so any upward move left the order unable to cross and it came
     * back "found no executable liquidity" - a rejection that looks like absent
     * liquidity but is really a cap set too tight to trade against.
     *
     * <p>The anchor is the order's limit price where it has one, so a routed
     * child is capped relative to the price the router chose rather than to a
     * reference that may be stale. Market orders have no limit and fall back to
     * the reference price.
     *
     * <p>Rounded away from the anchor - up for a buy, down for a sell - so tick
     * rounding can only widen the budget, never quietly tighten it below what
     * was asked for.
     */
    static long protectionPriceTicks(OrderView order, BigDecimal slippageBps) {
        ListingSnapshot listing = order.listing();
        BigDecimal anchor = order.limitPrice() != null ? order.limitPrice() : listing.referencePrice();
        BigDecimal tickSize = listing.tickSize();
        long scaledAnchor = FixedPointMath.toScaledLong(anchor);
        long scaledTickSize = FixedPointMath.toScaledLong(tickSize);
        long scaledBps = slippageBps == null ? -1L : slippageBps.longValueExact();
        if (scaledAnchor <= 0) {
            throw new IllegalArgumentException("protection price anchor must be positive");
        }
        if (scaledTickSize <= 0) {
            throw new IllegalArgumentException("tick size must be positive");
        }
        if (scaledBps < 0) {
            throw new IllegalArgumentException("slippage budget must be non-negative");
        }
        long scaledTolerance = FixedPointMath.applyBps(scaledAnchor, scaledBps);
        boolean buying = order.side() == OrderSide.BUY;
        long scaledCap = buying ? scaledAnchor + scaledTolerance : scaledAnchor - scaledTolerance;
        if (scaledCap <= 0) {
            throw new IllegalArgumentException("protection price must remain positive");
        }
        return buying
                ? FixedPointMath.divideCeiling(scaledCap, scaledTickSize, "protection price")
                : FixedPointMath.divideFloor(scaledCap, scaledTickSize, "protection price");
    }

    static long quantitySteps(BigDecimal quantity, ListingSnapshot listing) {
        return FixedPointMath.exactUnits(quantity, listing.sizeIncrement(), "quantity");
    }

    private static BigDecimal decimalPrice(long ticks, ListingSnapshot listing) {
        return listing.tickSize().multiply(BigDecimal.valueOf(ticks));
    }

    private static BigDecimal decimalQuantity(long steps, ListingSnapshot listing) {
        return listing.sizeIncrement().multiply(BigDecimal.valueOf(steps));
    }

    static OrderAction side(OrderSide side) {
        return side == OrderSide.BUY ? OrderAction.BID : OrderAction.ASK;
    }

    static int symbolId(ListingSnapshot listing) {
        return Math.toIntExact(listing.id());
    }

    static long coreOrderId(OrderView order) {
        return positiveLong(order.id());
    }

    static long clientId(OrderView order) {
        return positiveLong(UUID.nameUUIDFromBytes(
                order.ownerSubject().getBytes(StandardCharsets.UTF_8)));
    }

    static long deliveryId(OrderView order, String operation) {
        return positiveLong(ExecutionCommandPublisher.deterministic(
                order.id() + ":" + order.version() + ":" + operation));
    }

    private static long positiveLong(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES * 2)
                                      .putLong(uuid.getMostSignificantBits())
                                      .putLong(uuid.getLeastSignificantBits());
        buffer.flip();
        long value = buffer.getLong() & Long.MAX_VALUE;
        return value == 0 ? 1 : value;
    }

    /**
     * Maps a currency to its ISO 4217 numeric code, which is the identity
     * portfolio-service uses for balances.
     *
     * <p>The venue previously derived this with {@code stableInt("currency:USD")},
     * producing 1535516392, while every seeded balance lands under 840. The risk
     * engine then held {@code accounts:{840=<funded>, 1535516392=0}} and rejected
     * every buy with RISK_NSF regardless of how large the seed was, because the
     * quote currency the symbol asked for was always empty.
     *
     * <p>Deliberately throws on an unmapped currency rather than falling back to
     * a hash. A silent fallback is exactly what produced the original bug: the
     * venue and portfolio-service disagreed and nothing said so.
     *
     * <p>This table is the smaller half of the fix. The currency's ISO code
     * properly belongs on the listing in static-data-service; until it is
     * modelled there, this keeps the two subsystems consistent.
     */
    private static int isoCurrencyCode(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("listing currency is required to build an exchange-core symbol");
        }
        Integer code = ISO_4217.get(currency.strip().toUpperCase(Locale.ROOT));
        if (code == null) {
            throw new IllegalArgumentException(
                    "No ISO 4217 numeric code mapped for currency '" + currency + "'. Add it to ISO_4217, or the "
                            + "venue and portfolio-service will disagree on currency identity and every order "
                            + "will be rejected with RISK_NSF.");
        }
        return code;
    }

    private static int stableInt(String value) {
        long positive = positiveLong(ExecutionCommandPublisher.deterministic(value));
        return Math.toIntExact((positive % (Integer.MAX_VALUE - 1L)) + 1L);
    }

    private static String reference(OrderView order, String action) {
        return "XCORE-" + action + "-" + order.id() + ":" + order.version();
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
    }

    static boolean usesLocalRunStorage(Path storage) {
        for (Path segment : storage.toAbsolutePath().normalize()) {
            if (".local-run".equals(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    static boolean isProductionProfile(Collection<String> activeProfiles) {
        return activeProfiles.stream()
                .filter(Objects::nonNull)
                .map(profile -> profile.strip().toLowerCase(Locale.ROOT))
                .anyMatch(profile -> "prod".equals(profile) || "production".equals(profile));
    }

    static void validateProductionGuardrails(
            Path storage, long minFreeStorageBytes, Collection<String> activeProfiles) {
        if (!isProductionProfile(activeProfiles)) {
            return;
        }
        if (storage == null || storage.toString().isBlank()) {
            throw new IllegalStateException("Production exchange-core mode requires "
                    + "emporia.execution.exchange-core.storage-directory to point at persistent storage");
        }
        if (usesLocalRunStorage(storage)) {
            throw new IllegalStateException("Production exchange-core mode cannot use local-run storage: "
                    + storage.toAbsolutePath().normalize()
                    + ". Set EXCHANGE_CORE_STORAGE_DIRECTORY to a persistent volume.");
        }
        if (minFreeStorageBytes <= 0) {
            throw new IllegalStateException("Production exchange-core mode requires "
                    + "EXCHANGE_CORE_MIN_FREE_STORAGE_BYTES to be greater than 0");
        }
    }

    private record GatewaySpec(
            ExecutionCommandPublisher commands,
            ExchangeCoreVenue venue,
            boolean fullEquityRisk,
            TradingDataClient recoverySource,
            BigDecimal slippageBps,
            MeterRegistry meters) {
        private GatewaySpec {
            commands = Objects.requireNonNull(commands, "commands");
            venue = Objects.requireNonNull(venue, "venue");
            slippageBps = Objects.requireNonNull(slippageBps, "slippageBps");
            meters = Objects.requireNonNull(meters, "meters");
        }

        private static GatewayBuilder builder(ExecutionCommandPublisher commands, ExchangeCoreVenue venue) {
            return new GatewayBuilder(commands, venue);
        }
    }

    static final class GatewayBuilder {
        private final ExecutionCommandPublisher commands;
        private final ExchangeCoreVenue venue;
        private boolean fullEquityRisk;
        private TradingDataClient recoverySource;
        private BigDecimal slippageBps = DEFAULT_SLIPPAGE_BPS;
        private MeterRegistry meters = new SimpleMeterRegistry();

        private GatewayBuilder(ExecutionCommandPublisher commands, ExchangeCoreVenue venue) {
            this.commands = commands;
            this.venue = venue;
        }

        GatewayBuilder fullEquityRisk(boolean fullEquityRisk) {
            this.fullEquityRisk = fullEquityRisk;
            return this;
        }

        GatewayBuilder recoverySource(TradingDataClient recoverySource) {
            this.recoverySource = recoverySource;
            return this;
        }

        GatewayBuilder slippageBps(BigDecimal slippageBps) {
            this.slippageBps = Objects.requireNonNull(slippageBps, "slippageBps");
            return this;
        }

        GatewayBuilder meterRegistry(MeterRegistry meters) {
            this.meters = Objects.requireNonNull(meters, "meters");
            return this;
        }

        ExchangeCoreExecutionVenueGateway build() {
            return new ExchangeCoreExecutionVenueGateway(buildSpec());
        }

        private GatewaySpec buildSpec() {
            return new GatewaySpec(commands, venue, fullEquityRisk, recoverySource, slippageBps, meters);
        }
    }

    private record ProductionVenueSpec(
            String exchangeId,
            Path storage,
            int partitions,
            ProductionSimulationAccounting accounting,
            boolean journaling,
            int retainedCheckpoints,
            long minFreeStorageBytes,
            Set<String> activeProfiles) {
        private ProductionVenueSpec {
            exchangeId = Objects.requireNonNull(exchangeId, "exchangeId");
            storage = Objects.requireNonNull(storage, "storage");
            accounting = Objects.requireNonNull(accounting, "accounting");
            activeProfiles = Set.copyOf(activeProfiles);
        }

        private static ProductionVenueSpecBuilder builder() {
            return new ProductionVenueSpecBuilder();
        }
    }

    private static final class ProductionVenueSpecBuilder {
        private String exchangeId;
        private Path storage;
        private int partitions;
        private ProductionSimulationAccounting accounting;
        private boolean journaling;
        private int retainedCheckpoints = 2;
        private long minFreeStorageBytes;
        private Set<String> activeProfiles = Set.of();

        private ProductionVenueSpecBuilder exchangeId(String exchangeId) {
            this.exchangeId = exchangeId;
            return this;
        }

        private ProductionVenueSpecBuilder storage(Path storage) {
            this.storage = storage;
            return this;
        }

        private ProductionVenueSpecBuilder partitions(int partitions) {
            this.partitions = partitions;
            return this;
        }

        private ProductionVenueSpecBuilder accounting(ProductionSimulationAccounting accounting) {
            this.accounting = accounting;
            return this;
        }

        private ProductionVenueSpecBuilder journaling(boolean journaling) {
            this.journaling = journaling;
            return this;
        }

        private ProductionVenueSpecBuilder retainedCheckpoints(int retainedCheckpoints) {
            this.retainedCheckpoints = retainedCheckpoints;
            return this;
        }

        private ProductionVenueSpecBuilder minFreeStorageBytes(long minFreeStorageBytes) {
            this.minFreeStorageBytes = minFreeStorageBytes;
            return this;
        }

        private ProductionVenueSpecBuilder activeProfiles(Set<String> activeProfiles) {
            this.activeProfiles = Set.copyOf(activeProfiles);
            return this;
        }

        private ProductionVenueSpec build() {
            return new ProductionVenueSpec(exchangeId, storage, partitions, accounting,
                    journaling, retainedCheckpoints, minFreeStorageBytes, activeProfiles);
        }
    }

    interface ExchangeCoreVenue extends AutoCloseable {
        void addSymbols(Collection<CoreSymbolSpecification> symbols);

        /**
         * Replaces the venue's lifecycle projection with one rebuilt elsewhere.
         *
         * <p>Replaces rather than merges, so it must be applied before any
         * command is accepted.
         */
        void recoverLifecycle(DmaLifecycleSnapshot lifecycle);

        /**
         * Imports a client's balances from portfolio-service into the risk
         * engine. Required before that client's first order under
         * full-equity-risk: the risk engine rejects commands for a uid it has
         * never seen ("User profile {} not found"), which surfaces to the caller
         * as an ordinary order rejection.
         */
        CompletableFuture<Void> onboardPortfolio(long clientId);

        /**
         * Order IDs the matching engine currently has resting for this
         * client, keyed the same way {@link #coreOrderId} derives them from
         * an order-management order ID - for reconciling against an external
         * order-lifecycle record.
         */
        CompletableFuture<Set<Long>> openOrderIds(long clientId);

        CompletableFuture<ProductionSimulationResult> submit(DmaLimitOrder order);

        CompletableFuture<ProductionSimulationResult> submitProtected(DmaProtectedMarketOrder order);

        CompletableFuture<ProductionSimulationResult> replace(DmaReplaceOrder replacement);

        CompletableFuture<ProductionSimulationResult> cancel(DmaCancelOrder cancellation);

        Set<Integer> restoredSymbols();

        default Optional<ExchangeCoreCheckpointStore.StorageStats> checkpointStatus() {
            return Optional.empty();
        }

        default int retainedCheckpoints() {
            return 0;
        }

        void checkpoint();

        @Override
        void close();
    }

    private record Correlation(OrderView order) {
    }

    private static final class ProductionSimulationVenue implements ExchangeCoreVenue {
        private final ProductionSimulation simulation;
        private final ExchangeCoreCheckpointStore checkpointStore;
        private final Set<Integer> restoredSymbols;
        private final Set<Integer> knownSymbols = ConcurrentHashMap.newKeySet();
        private final AtomicLong checkpointSequence;
        private final boolean journaling;
        private final int retainedCheckpoints;
        private final long minFreeStorageBytes;

        private ProductionSimulationVenue(ProductionVenueSpec spec) throws IOException {
            if (spec.retainedCheckpoints() < 1) {
                throw new IllegalArgumentException("retainedCheckpoints must be at least 1");
            }
            if (spec.minFreeStorageBytes() < 0) {
                throw new IllegalArgumentException("minFreeStorageBytes must not be negative");
            }
            this.journaling = spec.journaling();
            this.retainedCheckpoints = spec.retainedCheckpoints();
            this.minFreeStorageBytes = spec.minFreeStorageBytes();
            ProductionSimulationConfiguration configuration =
                    ProductionSimulationConfiguration.create(
                            spec.exchangeId(), spec.storage(), spec.partitions(), spec.journaling());
            checkpointStore = new ExchangeCoreCheckpointStore(configuration.storageDirectory());
            ExchangeCoreCheckpointStore.LatestCheckpoint latest =
                    checkpointStore.load().orElse(null);
            long nextCheckpointBaseline = checkpointStore.maxCheckpointId();
            if (latest == null) {
                simulation = ProductionSimulation.start(configuration, spec.accounting());
                restoredSymbols = Set.of();
                checkpointSequence = new AtomicLong(nextCheckpointBaseline);
            } else {
                simulation = ProductionSimulation.recover(configuration, latest.checkpointId(), spec.accounting());
                restoredSymbols = latest.symbols();
                knownSymbols.addAll(restoredSymbols);
                checkpointSequence = new AtomicLong(Math.max(latest.checkpointId(), nextCheckpointBaseline));
            }
        }

        @Override
        public void addSymbols(Collection<CoreSymbolSpecification> symbols) {
            simulation.addSymbols(symbols);
            symbols.stream().map(symbol -> symbol.symbolId).forEach(knownSymbols::add);
            checkpoint();
        }

        @Override
        public void recoverLifecycle(DmaLifecycleSnapshot lifecycle) {
            simulation.recoverLifecycle(lifecycle);
        }

        @Override
        public CompletableFuture<Void> onboardPortfolio(long clientId) {
            return simulation.onboardPortfolio(clientId).thenApply(snapshot -> null);
        }

        @Override
        public CompletableFuture<Set<Long>> openOrderIds(long clientId) {
            return simulation.openOrderIds(clientId);
        }

        // Where durability comes from, and why the two are one switch.
        //
        // journaling=false: snapshot after every command, as before. The
        // snapshot is the only durability mechanism, so it cannot leave the
        // command path without losing data.
        //
        // journaling=true: the journal is a Disruptor stage running in parallel
        // with risk and matching, so a result waits on max(matching, journal)
        // rather than matching followed by a full-state serialisation, and
        // snapshots drop to a 60s timer.
        //
        // Removing the per-command snapshot without the journal would leave
        // nothing durable between timed snapshots, which is why one flag
        // controls both rather than two independent settings.
        //
        // The journal restores the matching engine; startup rebuilds the DMA
        // lifecycle projection from order-management before the listener opens.
        // Keep crash-recovery acceptance green before using this mode in
        // production, because a graceful shutdown checkpoint can hide journal
        // replay gaps.
        @Override
        public CompletableFuture<ProductionSimulationResult> submit(DmaLimitOrder order) {
            return checkpointUnlessJournalled(simulation.submit(order));
        }

        @Override
        public CompletableFuture<ProductionSimulationResult> submitProtected(DmaProtectedMarketOrder order) {
            return checkpointUnlessJournalled(simulation.submitProtected(order));
        }

        @Override
        public CompletableFuture<ProductionSimulationResult> replace(DmaReplaceOrder replacement) {
            return checkpointUnlessJournalled(simulation.replace(replacement));
        }

        @Override
        public CompletableFuture<ProductionSimulationResult> cancel(DmaCancelOrder cancellation) {
            return checkpointUnlessJournalled(simulation.cancel(cancellation));
        }

        @Override
        public Set<Integer> restoredSymbols() {
            return restoredSymbols;
        }

        @Override
        public Optional<ExchangeCoreCheckpointStore.StorageStats> checkpointStatus() {
            try {
                return Optional.of(checkpointStore.stats());
            } catch (IOException unavailable) {
                throw new ExchangeCoreCheckpointException(unavailable);
            }
        }

        @Override
        public int retainedCheckpoints() {
            return retainedCheckpoints;
        }

        @Override
        public synchronized void checkpoint() {
            try {
                checkpointStore.requireUsableSpace(minFreeStorageBytes);
                long checkpointId = checkpointSequence.incrementAndGet();
                ProductionSimulationCheckpoint checkpoint = simulation.checkpoint(checkpointId);
                checkpointStore.save(checkpoint.checkpointId(), knownSymbols);
                try {
                    checkpointStore.pruneRetainingLatest(retainedCheckpoints);
                } catch (IOException | RuntimeException pruneFailure) {
                    log.warn("Exchange-core checkpoint {} saved, but old checkpoint pruning failed: {}",
                            checkpoint.checkpointId(), pruneFailure.getMessage());
                }
            } catch (IOException error) {
                throw new ExchangeCoreCheckpointException(error);
            }
        }

        @Override
        public void close() {
            simulation.close();
        }

        private CompletableFuture<ProductionSimulationResult> checkpointUnlessJournalled(
                CompletableFuture<ProductionSimulationResult> operation) {
            if (journaling) {
                return operation;
            }
            return operation.thenApply(result -> {
                checkpoint();
                return result;
            }).exceptionally(error -> {
                throw new CompletionException(ExchangeCoreExecutionVenueGateway.unwrap(error));
            });
        }
    }

    static final class ExchangeCoreCheckpointException extends RuntimeException {
        ExchangeCoreCheckpointException(String message, Throwable cause) {
            super(message, cause);
        }

        ExchangeCoreCheckpointException(IOException cause) {
            super("Exchange-core checkpoint failed", cause);
        }
    }
}

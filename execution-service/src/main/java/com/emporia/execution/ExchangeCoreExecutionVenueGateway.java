package com.emporia.execution;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.dma.DmaCancelOrder;
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
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
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
public class ExchangeCoreExecutionVenueGateway implements ExecutionVenueGateway, SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ExchangeCoreExecutionVenueGateway.class);
    private static final String ACCOUNTING_FULL_EQUITY = "full-equity-risk";

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

    @Autowired
    public ExchangeCoreExecutionVenueGateway(
            ExecutionCommandPublisher commands,
            ServiceAccessTokenProvider tokenProvider,
            Optional<DataSource> dataSource,
            @Value("${emporia.execution.exchange-core.exchange-id}") String exchangeId,
            @Value("${emporia.execution.exchange-core.storage-directory}") Path storage,
            @Value("${emporia.execution.exchange-core.symbol-partitions}") int partitions,
            @Value("${emporia.execution.exchange-core.accounting-mode:matching-only}") String accountingMode,
            @Value("${emporia.execution.exchange-core.portfolio-url:}") String portfolioUrl,
            @Value("${emporia.execution.exchange-core.portfolio-request-timeout:3s}") Duration portfolioTimeout)
            throws IOException {
        this(commands, buildVenue(exchangeId, storage, partitions,
                buildAccounting(accountingMode, exchangeId, portfolioUrl, portfolioTimeout, tokenProvider, dataSource.orElse(null))),
                ACCOUNTING_FULL_EQUITY.equalsIgnoreCase(accountingMode));
        log.info("Exchange-core venue started with accounting-mode={}", accountingMode);
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

    private static ExchangeCoreVenue buildVenue(
            String exchangeId, Path storage, int partitions,
            ProductionSimulationAccounting accounting) throws IOException {
        // exchange-core writes its snapshots straight into this directory and does
        // not create it. Only ExchangeCoreCheckpointStore.save did, and that runs
        // after the snapshot write has already failed.
        //
        // The failure mode is quiet and expensive: the directory lives under
        // target/, so any mvn clean removes it, after which every order reaches
        // the venue, fails to checkpoint, and comes back REJECTED. The service
        // starts normally and the submit still returns 201, so it looks like a
        // trading rejection rather than missing scratch space.
        Files.createDirectories(storage);
        return new ProductionSimulationVenue(exchangeId, storage, partitions, accounting);
    }

    /** Matching-only wiring; no client onboarding, since there are no risk profiles. */
    public ExchangeCoreExecutionVenueGateway(ExecutionCommandPublisher commands, ExchangeCoreVenue venue) {
        this(commands, venue, false);
    }

    public ExchangeCoreExecutionVenueGateway(ExecutionCommandPublisher commands, ExchangeCoreVenue venue,
                                             boolean fullEquityRisk) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.venue = Objects.requireNonNull(venue, "venue");
        this.fullEquityRisk = fullEquityRisk;
        this.symbols.addAll(venue.restoredSymbols());
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
            if (order.type() == OrderType.MARKET) {
                DmaProtectedMarketOrder request = new DmaProtectedMarketOrder(
                        deliveryId(order, "submit-protected"),
                        coreOrderId(order),
                        clientId(order),
                        symbolId(order.listing()),
                        side(order.side()),
                        protectionPriceTicks(order),
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

    @Override
    public void start() {
        running.set(true);
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

    private boolean checkpointFailed(OrderView order, String operation, RuntimeException failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof ExchangeCoreCheckpointException) {
            log.error("Exchange-core {} for order {} reached the venue, but checkpointing failed",
                    operation, order.id(), cause);
            return true;
        }
        return false;
    }

    private void ensureSymbol(ListingSnapshot listing) {
        int symbol = symbolId(listing);
        if (symbols.add(symbol)) {
            venue.addSymbols(Set.of(CoreSymbolSpecification.builder()
                    .symbolId(symbol)
                    .type(SymbolType.EQUITY)
                    .baseCurrency(stableInt("asset:" + listing.marketSymbol()))
                    .quoteCurrency(stableInt("currency:" + listing.currency()))
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

    private static long priceTicks(OrderView order) {
        return exactUnits(order.limitPrice(), order.listing().tickSize(), "limit price");
    }

    private static long protectionPriceTicks(OrderView order) {
        return exactUnits(order.listing().referencePrice(), order.listing().tickSize(), "reference price");
    }

    private static long quantitySteps(BigDecimal quantity, ListingSnapshot listing) {
        return exactUnits(quantity, listing.sizeIncrement(), "quantity");
    }

    private static long exactUnits(BigDecimal value, BigDecimal increment, String field) {
        if (value == null || increment == null || value.signum() <= 0 || increment.signum() <= 0) {
            throw new IllegalArgumentException(field + " and increment must be positive");
        }
        return value.divide(increment, 0, RoundingMode.UNNECESSARY).longValueExact();
    }

    private static BigDecimal decimalPrice(long ticks, ListingSnapshot listing) {
        return listing.tickSize().multiply(BigDecimal.valueOf(ticks));
    }

    private static BigDecimal decimalQuantity(long steps, ListingSnapshot listing) {
        return listing.sizeIncrement().multiply(BigDecimal.valueOf(steps));
    }

    private static OrderAction side(OrderSide side) {
        return side == OrderSide.BUY ? OrderAction.BID : OrderAction.ASK;
    }

    private static int symbolId(ListingSnapshot listing) {
        return Math.toIntExact(listing.id());
    }

    private static long coreOrderId(OrderView order) {
        return positiveLong(order.id());
    }

    private static long clientId(OrderView order) {
        return positiveLong(UUID.nameUUIDFromBytes(
                order.ownerSubject().getBytes(StandardCharsets.UTF_8)));
    }

    private static long deliveryId(OrderView order, String operation) {
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

    interface ExchangeCoreVenue extends AutoCloseable {
        void addSymbols(Collection<CoreSymbolSpecification> symbols);

        /**
         * Imports a client's balances from portfolio-service into the risk
         * engine. Required before that client's first order under
         * full-equity-risk: the risk engine rejects commands for a uid it has
         * never seen ("User profile {} not found"), which surfaces to the caller
         * as an ordinary order rejection.
         */
        CompletableFuture<Void> onboardPortfolio(long clientId);

        CompletableFuture<ProductionSimulationResult> submit(DmaLimitOrder order);

        CompletableFuture<ProductionSimulationResult> submitProtected(DmaProtectedMarketOrder order);

        CompletableFuture<ProductionSimulationResult> replace(DmaReplaceOrder replacement);

        CompletableFuture<ProductionSimulationResult> cancel(DmaCancelOrder cancellation);

        Set<Integer> restoredSymbols();

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

        private ProductionSimulationVenue(
                String exchangeId, Path storage, int partitions,
                ProductionSimulationAccounting accounting) throws IOException {
            ProductionSimulationConfiguration configuration =
                    ProductionSimulationConfiguration.create(exchangeId, storage, partitions);
            checkpointStore = new ExchangeCoreCheckpointStore(configuration.storageDirectory());
            ExchangeCoreCheckpointStore.LatestCheckpoint latest =
                    checkpointStore.load().orElse(null);
            if (latest == null) {
                simulation = ProductionSimulation.start(configuration, accounting);
                restoredSymbols = Set.of();
                checkpointSequence = new AtomicLong(0);
            } else {
                simulation = ProductionSimulation.recover(configuration, latest.checkpointId(), accounting);
                restoredSymbols = latest.symbols();
                knownSymbols.addAll(restoredSymbols);
                checkpointSequence = new AtomicLong(latest.checkpointId());
            }
        }

        @Override
        public void addSymbols(Collection<CoreSymbolSpecification> symbols) {
            simulation.addSymbols(symbols);
            symbols.stream().map(symbol -> symbol.symbolId).forEach(knownSymbols::add);
            checkpoint();
        }

        @Override
        public CompletableFuture<Void> onboardPortfolio(long clientId) {
            return simulation.onboardPortfolio(clientId).thenApply(snapshot -> null);
        }

        @Override
        public CompletableFuture<ProductionSimulationResult> submit(DmaLimitOrder order) {
            return checkpointAfter(simulation.submit(order));
        }

        @Override
        public CompletableFuture<ProductionSimulationResult> submitProtected(DmaProtectedMarketOrder order) {
            return checkpointAfter(simulation.submitProtected(order));
        }

        @Override
        public CompletableFuture<ProductionSimulationResult> replace(DmaReplaceOrder replacement) {
            return checkpointAfter(simulation.replace(replacement));
        }

        @Override
        public CompletableFuture<ProductionSimulationResult> cancel(DmaCancelOrder cancellation) {
            return checkpointAfter(simulation.cancel(cancellation));
        }

        @Override
        public Set<Integer> restoredSymbols() {
            return restoredSymbols;
        }

        @Override
        public void checkpoint() {
            try {
                long checkpointId = checkpointSequence.incrementAndGet();
                ProductionSimulationCheckpoint checkpoint = simulation.checkpoint(checkpointId);
                checkpointStore.save(checkpoint.checkpointId(), knownSymbols);
            } catch (IOException error) {
                throw new ExchangeCoreCheckpointException(error);
            }
        }

        @Override
        public void close() {
            simulation.close();
        }

        private CompletableFuture<ProductionSimulationResult> checkpointAfter(
                CompletableFuture<ProductionSimulationResult> operation) {
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

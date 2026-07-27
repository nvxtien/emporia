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
import exchange.core2.core.simulation.ProductionSimulationConfiguration;
import exchange.core2.core.simulation.ProductionSimulationResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name = "emporia.execution.venue-mode", havingValue = "exchange-core")
class ExchangeCoreExecutionVenueGateway implements ExecutionVenueGateway, SmartLifecycle {
    private final ExecutionCommandPublisher commands;
    private final ExchangeCoreVenue venue;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Set<Integer> symbols = ConcurrentHashMap.newKeySet();
    private final Map<Long, Correlation> correlations = new ConcurrentHashMap<>();

    ExchangeCoreExecutionVenueGateway(ExecutionCommandPublisher commands,
                                      @Value("${emporia.execution.exchange-core.exchange-id}") String exchangeId,
                                      @Value("${emporia.execution.exchange-core.storage-directory}") Path storage,
                                      @Value("${emporia.execution.exchange-core.symbol-partitions}") int partitions)
            throws IOException {
        this(commands, new ProductionSimulationVenue(exchangeId, storage, partitions));
    }

    ExchangeCoreExecutionVenueGateway(ExecutionCommandPublisher commands, ExchangeCoreVenue venue) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.venue = Objects.requireNonNull(venue, "venue");
    }

    @Override
    public void submit(OrderView order) {
        try {
            remember(order);
            ensureSymbol(order.listing());
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
            venue.close();
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

    interface ExchangeCoreVenue extends AutoCloseable {
        void addSymbols(Collection<CoreSymbolSpecification> symbols);

        CompletableFuture<ProductionSimulationResult> submit(DmaLimitOrder order);

        CompletableFuture<ProductionSimulationResult> submitProtected(DmaProtectedMarketOrder order);

        CompletableFuture<ProductionSimulationResult> replace(DmaReplaceOrder replacement);

        CompletableFuture<ProductionSimulationResult> cancel(DmaCancelOrder cancellation);

        @Override
        void close();
    }

    private record Correlation(OrderView order) {
    }

    private static final class ProductionSimulationVenue implements ExchangeCoreVenue {
        private final ProductionSimulation simulation;

        private ProductionSimulationVenue(String exchangeId, Path storage, int partitions)
                throws IOException {
            simulation = ProductionSimulation.start(
                    ProductionSimulationConfiguration.create(exchangeId, storage, partitions));
        }

        @Override
        public void addSymbols(Collection<CoreSymbolSpecification> symbols) {
            simulation.addSymbols(symbols);
        }

        @Override
        public CompletableFuture<ProductionSimulationResult> submit(DmaLimitOrder order) {
            return simulation.submit(order);
        }

        @Override
        public CompletableFuture<ProductionSimulationResult> submitProtected(DmaProtectedMarketOrder order) {
            return simulation.submitProtected(order);
        }

        @Override
        public CompletableFuture<ProductionSimulationResult> replace(DmaReplaceOrder replacement) {
            return simulation.replace(replacement);
        }

        @Override
        public CompletableFuture<ProductionSimulationResult> cancel(DmaCancelOrder cancellation) {
            return simulation.cancel(cancellation);
        }

        @Override
        public void close() {
            simulation.close();
        }
    }
}

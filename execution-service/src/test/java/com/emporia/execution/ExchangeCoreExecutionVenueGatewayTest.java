package com.emporia.execution;

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
import exchange.core2.core.common.api.dma.DmaReplaceOrder;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.simulation.ProductionSimulationResult;
import exchange.core2.core.simulation.SimulationOperation;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeCoreExecutionVenueGatewayTest {

    private final RecordingCommands commands = new RecordingCommands();
    private final FakeVenue venue = new FakeVenue();
    private final ExchangeCoreExecutionVenueGateway gateway =
            new ExchangeCoreExecutionVenueGateway(commands, venue);

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
    void rejectsMarketOrdersUntilProtectedIocMappingIsEnabled() {
        OrderView order = order(OrderSide.BUY, OrderType.MARKET, "10", null);

        gateway.submit(order);

        assertThat(venue.submits).isEmpty();
        assertThat(commands.rejections)
                .containsExactly(new Rejection(order.id(), order.deskId(), order.listing().exchangeMic(),
                        "Exchange-core submit failed: only LIMIT orders are enabled in the first exchange-core integration step"));
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
        return new ListingSnapshot(
                7, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq",
                "US", "USD", new BigDecimal("0.01"), BigDecimal.ONE,
                new BigDecimal("101"), new BigDecimal("100"));
    }

    private static final class FakeVenue implements ExchangeCoreExecutionVenueGateway.ExchangeCoreVenue {
        private final Queue<Function<DmaLimitOrder, ProductionSimulationResult>> submitResponses = new ArrayDeque<>();
        private final ArrayDeque<Collection<CoreSymbolSpecification>> symbols = new ArrayDeque<>();
        private final ArrayDeque<DmaLimitOrder> submits = new ArrayDeque<>();

        @Override
        public void addSymbols(Collection<CoreSymbolSpecification> symbols) {
            this.symbols.add(symbols);
        }

        @Override
        public CompletableFuture<ProductionSimulationResult> submit(DmaLimitOrder order) {
            submits.add(order);
            return CompletableFuture.completedFuture(submitResponses.remove().apply(order));
        }

        @Override
        public CompletableFuture<ProductionSimulationResult> replace(DmaReplaceOrder replacement) {
            throw new UnsupportedOperationException("replace is not needed in these tests");
        }

        @Override
        public CompletableFuture<ProductionSimulationResult> cancel(DmaCancelOrder cancellation) {
            throw new UnsupportedOperationException("cancel is not needed in these tests");
        }

        @Override
        public void close() {
        }
    }

    private static final class RecordingCommands extends ExecutionCommandPublisher {
        private final ArrayDeque<Fill> fills = new ArrayDeque<>();
        private final ArrayDeque<Rejection> rejections = new ArrayDeque<>();

        private RecordingCommands() {
            super(null, "executions", new SimpleMeterRegistry());
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
    }

    private record Fill(UUID orderId, BigDecimal quantity, BigDecimal price, String venue) {
    }

    private record Rejection(UUID orderId, String deskId, String venue, String detail) {
    }
}

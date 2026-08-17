package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LiveDirectOrdersTest {

    private final TradingOrderRepository orders = mock(TradingOrderRepository.class);
    private final LiveDirectOrders liveDirectOrders = new LiveDirectOrders(orders);

    /**
     * The gap that left 2,340 live child orders answering "unknown lifecycle
     * order" to every operation: the venue holds them, so the venue has to be
     * told about them.
     */
    @Test
    void includesChildOrdersBecauseTheVenueIsHoldingThem() {
        TradingOrder parent = order(null, "DMA");
        TradingOrder child = order(parent.getId(), "DMA");
        when(orders.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(List.of(parent, child));

        List<UUID> ids = liveDirectOrders.current().stream().map(OrderView::id).toList();

        assertEquals(2, ids.size(), "a child resting at the venue must be in the set");
        assertTrue(ids.contains(child.getId()));
    }

    @Test
    void excludesOrdersRoutedThroughAStrategyRatherThanToTheVenue() {
        TradingOrder direct = order(null, "DMA");
        when(orders.findByStatusInOrderByCreatedAtAsc(any()))
                .thenReturn(List.of(direct, order(null, "SMART"), order(null, "VWAP")));

        assertEquals(List.of(direct.getId()),
                liveDirectOrders.current().stream().map(OrderView::id).toList());
    }

    /** parents() answers a different question and must keep answering it. */
    @Test
    void parentsStillReturnsTopLevelOrdersOfEveryDestination() {
        when(orders.findByStatusInAndParentOrderIdIsNullOrderByCreatedAtAsc(any()))
                .thenReturn(List.of(order(null, "DMA"), order(null, "VWAP")));

        assertEquals(2, liveDirectOrders.parents().size());
        verify(orders, never()).findByStatusInOrderByCreatedAtAsc(any());
    }

    @Test
    void isDirectIsCaseInsensitive() {
        assertTrue(LiveDirectOrders.isDirect(order(null, "dma")));
        assertFalse(LiveDirectOrders.isDirect(order(null, "SMART")));
    }

    private static TradingOrder order(UUID parentId, String destination) {
        ListingSnapshot listing = new ListingSnapshot(7, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS",
                "Nasdaq", "US", "USD", new BigDecimal("0.01"), BigDecimal.ONE,
                new BigDecimal("101"), new BigDecimal("100"));
        return new TradingOrder(UUID.randomUUID(), "trader-alpha", "desk-a", listing,
                OrderSide.BUY, OrderType.LIMIT, 100_000_000L, 10_000_000_000L, destination,
                "ref-" + UUID.randomUUID(), parentId, parentId, "{}");
    }
}

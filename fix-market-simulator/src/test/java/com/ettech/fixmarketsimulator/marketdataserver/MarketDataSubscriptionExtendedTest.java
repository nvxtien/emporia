package com.ettech.fixmarketsimulator.marketdataserver;

import com.ettech.fixmarketsimulator.exchange.*;
import com.ettech.fixmarketsimulator.exchange.impl.ExchangeImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.fixprotocol.components.MarketData;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketDataSubscriptionExtendedTest {

    private Connection mockConnection;
    private OrderBook book;

    @BeforeEach
    void setUp() {
        mockConnection = mock(Connection.class);
        book = new ExchangeImpl().getOrderBook("AAPL");
    }

    // -----------------------------------------------------------------------
    // getMdEntryType enum mapping
    // -----------------------------------------------------------------------

    @Test
    void getMdEntryType_Bid_returnsBid() {
        assertEquals(MarketData.MDEntryTypeEnum.MD_ENTRY_TYPE_BID,
                MarketDataSubscription.getMdEntryType(MdEntryType.Bid));
    }

    @Test
    void getMdEntryType_Offer_returnsOffer() {
        assertEquals(MarketData.MDEntryTypeEnum.MD_ENTRY_TYPE_OFFER,
                MarketDataSubscription.getMdEntryType(MdEntryType.Offer));
    }

    @Test
    void getMdEntryType_Trade_returnsTrade() {
        assertEquals(MarketData.MDEntryTypeEnum.MD_ENTRY_TYPE_TRADE,
                MarketDataSubscription.getMdEntryType(MdEntryType.Trade));
    }

    @Test
    void getMdEntryType_TradeVolume_returnsTradeVolume() {
        assertEquals(MarketData.MDEntryTypeEnum.MD_ENTRY_TYPE_TRADE_VOLUME,
                MarketDataSubscription.getMdEntryType(MdEntryType.TradeVolume));
    }

    // -----------------------------------------------------------------------
    // getMDUpdateActionEnum mapping
    // -----------------------------------------------------------------------

    @Test
    void getMDUpdateActionEnum_Add_returnsNew() {
        MarketDataSubscription sub = new MarketDataSubscription(mockConnection, book, "REQ-1");
        assertEquals(MarketData.MDUpdateActionEnum.MD_UPDATE_ACTION_NEW,
                sub.getMDUpdateActionEnum(MdUpdateActionType.Add));
    }

    @Test
    void getMDUpdateActionEnum_Modify_returnsChange() {
        MarketDataSubscription sub = new MarketDataSubscription(mockConnection, book, "REQ-1");
        assertEquals(MarketData.MDUpdateActionEnum.MD_UPDATE_ACTION_CHANGE,
                sub.getMDUpdateActionEnum(MdUpdateActionType.Modify));
    }

    @Test
    void getMDUpdateActionEnum_Remove_returnsDelete() {
        MarketDataSubscription sub = new MarketDataSubscription(mockConnection, book, "REQ-1");
        assertEquals(MarketData.MDUpdateActionEnum.MD_UPDATE_ACTION_DELETE,
                sub.getMDUpdateActionEnum(MdUpdateActionType.Remove));
    }

    // -----------------------------------------------------------------------
    // Constructor sends initial snapshot
    // -----------------------------------------------------------------------

    @Test
    void constructor_sendsInitialSnapshotOnConnection() {
        // book has orders - snapshot should be sent
        book.addOrder(Side.Buy, 10, new BigDecimal("99.00"), "CL-BUY");
        book.addOrder(Side.Sell, 5, new BigDecimal("101.00"), "CL-SELL");

        new MarketDataSubscription(mockConnection, book, "REQ-SNAP");

        // verify that connection.send was called with the initial snapshot
        verify(mockConnection, atLeastOnce()).send(any(MarketData.MarketDataIncrementalRefresh.class));
    }

    @Test
    void constructor_sendsInitialSnapshotWithLastTrade() {
        // Execute a trade to set lastTrade
        book.addOrder(Side.Buy, 10, new BigDecimal("100.00"), "CL-BUY");
        book.addOrder(Side.Sell, 10, new BigDecimal("100.00"), "CL-SELL");

        // lastTrade should be set now
        assertNotNull(book.getLastTrade());

        new MarketDataSubscription(mockConnection, book, "REQ-TRADE");
        verify(mockConnection, atLeastOnce()).send(any(MarketData.MarketDataIncrementalRefresh.class));
    }

    // -----------------------------------------------------------------------
    // close removes listener
    // -----------------------------------------------------------------------

    @Test
    void close_removesMdEntryListenerFromBook() {
        MarketDataSubscription sub = new MarketDataSubscription(mockConnection, book, "REQ-CLOSE");

        // Add a second listener to track how many are called
        List<List<MDEntry>> received = new ArrayList<>();
        book.addMdEntryListener(received::add);

        // Subscribe → one send on initial
        reset(mockConnection);

        // Now close the subscription
        sub.close();

        // Add an order — the subscription listener should NOT receive it anymore
        book.addOrder(Side.Buy, 5, new BigDecimal("50.00"), "CL-POST-CLOSE");

        // Connection should NOT be called after close
        verify(mockConnection, never()).send(any());
    }

    // -----------------------------------------------------------------------
    // onMdEntries dispatches updates
    // -----------------------------------------------------------------------

    @Test
    void onMdEntries_sendsUpdateToConnection() {
        MarketDataSubscription sub = new MarketDataSubscription(mockConnection, book, "REQ-UPDATE");
        reset(mockConnection); // ignore initial snapshot

        // Add an order which fires mdEntries via the listener
        book.addOrder(Side.Buy, 10, new BigDecimal("99.00"), "CL-NEW-ORDER");

        // Verify connection received the incremental update
        verify(mockConnection, atLeastOnce()).send(any(MarketData.MarketDataIncrementalRefresh.class));
    }

    @Test
    void onMdEntries_withModifyEntry_sendsUpdate() {
        book.addOrder(Side.Buy, 10, new BigDecimal("99.00"), "CL-INIT");
        MarketDataSubscription sub = new MarketDataSubscription(mockConnection, book, "REQ-MOD");
        reset(mockConnection);

        // Trigger a modify by adding orders that partially fill
        book.addOrder(Side.Sell, 5, new BigDecimal("99.00"), "CL-FILL-PART");

        verify(mockConnection, atLeastOnce()).send(any(MarketData.MarketDataIncrementalRefresh.class));
    }

    // -----------------------------------------------------------------------
    // getFixDecimal64 additional edge cases
    // -----------------------------------------------------------------------

    @Test
    void getFixDecimal64_integerValue_zeroExponent() {
        var fd = MarketDataSubscription.getFixDecimal64(new BigDecimal("100"));
        assertEquals(100L, fd.getMantissa());
        assertEquals(0, fd.getExponent());
    }

    @Test
    void getFixDecimal64_negativeInteger() {
        var fd = MarketDataSubscription.getFixDecimal64(new BigDecimal("-50"));
        assertEquals(-50L, fd.getMantissa());
        assertEquals(0, fd.getExponent());
    }
}

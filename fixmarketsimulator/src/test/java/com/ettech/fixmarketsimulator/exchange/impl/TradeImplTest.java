package com.ettech.fixmarketsimulator.exchange.impl;

import com.ettech.fixmarketsimulator.exchange.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class TradeImplTest {

    private TradeImpl createTrade() {
        return new TradeImpl(
                "trade-001",
                "cl-order-001",
                new BigDecimal("45.75"),
                200,
                "MSFT",
                Side.Buy,
                "order-001",
                50,
                150
        );
    }

    @Test
    void getTradeId_returnsCorrectValue() {
        assertEquals("trade-001", createTrade().getTradeId());
    }

    @Test
    void getClOrderId_returnsCorrectValue() {
        assertEquals("cl-order-001", createTrade().getClOrderId());
    }

    @Test
    void getPrice_returnsCorrectValue() {
        assertEquals(new BigDecimal("45.75"), createTrade().getPrice());
    }

    @Test
    void getQuantity_returnsCorrectValue() {
        assertEquals(200, createTrade().getQuantity(), 0.001);
    }

    @Test
    void getInstrument_returnsCorrectValue() {
        assertEquals("MSFT", createTrade().getInstrument());
    }

    @Test
    void getOrderSide_returnsCorrectValue() {
        assertEquals(Side.Buy, createTrade().getOrderSide());
    }

    @Test
    void getOrderId_returnsCorrectValue() {
        assertEquals("order-001", createTrade().getOrderId());
    }

    @Test
    void getLeavesQty_returnsCorrectValue() {
        assertEquals(50, createTrade().getLeavesQty(), 0.001);
    }

    @Test
    void getCumQty_returnsCorrectValue() {
        assertEquals(150, createTrade().getCumQty(), 0.001);
    }

    @Test
    void sellSide_trade_isStoredCorrectly() {
        TradeImpl trade = new TradeImpl("t", "cl", BigDecimal.TEN, 10, "SYM", Side.Sell, "o", 0, 10);
        assertEquals(Side.Sell, trade.getOrderSide());
    }
}

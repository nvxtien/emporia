package com.ettech.fixmarketsimulator.exchange.impl;

import com.ettech.fixmarketsimulator.exchange.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class LimitOrderImplTest {

    private LimitOrderImpl createOrder(double qty, double price, String clOrdId, Side side, String orderId) {
        return new LimitOrderImpl(qty, new BigDecimal(String.valueOf(price)), clOrdId, side, orderId);
    }

    @Test
    void constructor_setsAllFieldsCorrectly() {
        LimitOrderImpl order = createOrder(100, 50.5, "CL001", Side.Buy, "ORD001");

        assertEquals(100, order.getQuantity());
        assertEquals(100, order.getRemainingQty()); // initially equal to qty
        assertEquals(new BigDecimal("50.5"), order.getPrice());
        assertEquals("CL001", order.getClOrdId());
        assertEquals(Side.Buy, order.getSide());
        assertEquals("ORD001", order.getOrderId());
    }

    @Test
    void setQuantity_updatesQuantity() {
        LimitOrderImpl order = createOrder(100, 50, "CL001", Side.Buy, "ORD001");
        order.setQuantity(200);
        assertEquals(200, order.getQuantity());
    }

    @Test
    void setRemainingQty_updatesRemainingQty() {
        LimitOrderImpl order = createOrder(100, 50, "CL001", Side.Buy, "ORD001");
        order.setRemainingQty(60);
        assertEquals(60, order.getRemainingQty());
    }

    @Test
    void setPrice_updatesPrice() {
        LimitOrderImpl order = createOrder(100, 50, "CL001", Side.Buy, "ORD001");
        order.setPrice(new BigDecimal("75.25"));
        assertEquals(new BigDecimal("75.25"), order.getPrice());
    }

    @Test
    void setClOrdId_updatesClOrdId() {
        LimitOrderImpl order = createOrder(100, 50, "CL001", Side.Buy, "ORD001");
        order.setClOrdId("CL999");
        assertEquals("CL999", order.getClOrdId());
    }

    @Test
    void setSide_updatesSide() {
        LimitOrderImpl order = createOrder(100, 50, "CL001", Side.Buy, "ORD001");
        order.setSide(Side.Sell);
        assertEquals(Side.Sell, order.getSide());
    }

    @Test
    void setOrderId_updatesOrderId() {
        LimitOrderImpl order = createOrder(100, 50, "CL001", Side.Buy, "ORD001");
        order.setOrderId("ORD999");
        assertEquals("ORD999", order.getOrderId());
    }

    @Test
    void equals_sameOrderId_returnsTrue() {
        LimitOrderImpl order1 = createOrder(100, 50, "CL001", Side.Buy, "ORD001");
        LimitOrderImpl order2 = createOrder(200, 75, "CL002", Side.Sell, "ORD001");
        assertEquals(order1, order2);
    }

    @Test
    void equals_differentOrderId_returnsFalse() {
        LimitOrderImpl order1 = createOrder(100, 50, "CL001", Side.Buy, "ORD001");
        LimitOrderImpl order2 = createOrder(100, 50, "CL001", Side.Buy, "ORD002");
        assertNotEquals(order1, order2);
    }

    @Test
    void equals_sameObject_returnsTrue() {
        LimitOrderImpl order = createOrder(100, 50, "CL001", Side.Buy, "ORD001");
        assertEquals(order, order);
    }

    @Test
    void equals_null_returnsFalse() {
        LimitOrderImpl order = createOrder(100, 50, "CL001", Side.Buy, "ORD001");
        assertNotEquals(null, order);
    }

    @Test
    void equals_differentClass_returnsFalse() {
        LimitOrderImpl order = createOrder(100, 50, "CL001", Side.Buy, "ORD001");
        assertNotEquals("not an order", order);
    }

    @Test
    void hashCode_sameOrderId_sameHashCode() {
        LimitOrderImpl order1 = createOrder(100, 50, "CL001", Side.Buy, "ORD001");
        LimitOrderImpl order2 = createOrder(200, 75, "CL002", Side.Sell, "ORD001");
        assertEquals(order1.hashCode(), order2.hashCode());
    }

    @Test
    void sellSide_isStoredCorrectly() {
        LimitOrderImpl order = createOrder(50, 100, "CL002", Side.Sell, "ORD002");
        assertEquals(Side.Sell, order.getSide());
    }
}

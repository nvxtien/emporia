package com.ettech.fixmarketsimulator.exchange.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderDeleteImplTest {

    @Test
    void getOrderId_returnsCorrectValue() {
        OrderDeleteImpl od = new OrderDeleteImpl("order-001", 30, 100);
        assertEquals("order-001", od.getOrderId());
    }

    @Test
    void getLeavesQty_returnsCorrectValue() {
        OrderDeleteImpl od = new OrderDeleteImpl("order-001", 30, 100);
        assertEquals(30, od.getLeavesQty(), 0.001);
    }

    @Test
    void getQty_returnsCorrectValue() {
        OrderDeleteImpl od = new OrderDeleteImpl("order-001", 30, 100);
        assertEquals(100, od.getQty(), 0.001);
    }

    @Test
    void zeroLeavesQty_fullyFilled() {
        OrderDeleteImpl od = new OrderDeleteImpl("order-abc", 0, 50);
        assertEquals(0, od.getLeavesQty(), 0.001);
        assertEquals(50, od.getQty(), 0.001);
    }

    @Test
    void allFieldsStoredIndependently() {
        OrderDeleteImpl od1 = new OrderDeleteImpl("A", 10, 20);
        OrderDeleteImpl od2 = new OrderDeleteImpl("B", 5, 15);
        assertEquals("A", od1.getOrderId());
        assertEquals("B", od2.getOrderId());
        assertEquals(10, od1.getLeavesQty(), 0.001);
        assertEquals(5, od2.getLeavesQty(), 0.001);
    }
}

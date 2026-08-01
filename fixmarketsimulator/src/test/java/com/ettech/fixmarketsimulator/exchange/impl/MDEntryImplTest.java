package com.ettech.fixmarketsimulator.exchange.impl;

import com.ettech.fixmarketsimulator.exchange.MdEntryType;
import com.ettech.fixmarketsimulator.exchange.MdUpdateActionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class MDEntryImplTest {

    private MDEntryImpl createEntry() {
        return new MDEntryImpl(
                MdUpdateActionType.Add,
                "entry-001",
                new BigDecimal("99.50"),
                500,
                "AAPL",
                MdEntryType.Bid,
                "cl-order-001"
        );
    }

    @Test
    void getInstrument_returnsCorrectValue() {
        assertEquals("AAPL", createEntry().getInstrument());
    }

    @Test
    void getMdUpdateAction_returnsCorrectValue() {
        assertEquals(MdUpdateActionType.Add, createEntry().getMdUpdateAction());
    }

    @Test
    void getMdEntryType_returnsCorrectValue() {
        assertEquals(MdEntryType.Bid, createEntry().getMdEntryType());
    }

    @Test
    void getId_returnsCorrectValue() {
        assertEquals("entry-001", createEntry().getId());
    }

    @Test
    void getClOrderId_returnsCorrectValue() {
        assertEquals("cl-order-001", createEntry().getClOrderId());
    }

    @Test
    void getPrice_returnsCorrectValue() {
        assertEquals(new BigDecimal("99.50"), createEntry().getPrice());
    }

    @Test
    void getQuantity_returnsCorrectValue() {
        assertEquals(500, createEntry().getQuantity(), 0.001);
    }

    @Test
    void modifyAction_isStoredCorrectly() {
        MDEntryImpl entry = new MDEntryImpl(MdUpdateActionType.Modify, "id", BigDecimal.TEN, 100, "SYM", MdEntryType.Offer, "cl");
        assertEquals(MdUpdateActionType.Modify, entry.getMdUpdateAction());
        assertEquals(MdEntryType.Offer, entry.getMdEntryType());
    }

    @Test
    void removeAction_isStoredCorrectly() {
        MDEntryImpl entry = new MDEntryImpl(MdUpdateActionType.Remove, "id", BigDecimal.ONE, 10, "SYM", MdEntryType.Trade, "cl");
        assertEquals(MdUpdateActionType.Remove, entry.getMdUpdateAction());
        assertEquals(MdEntryType.Trade, entry.getMdEntryType());
    }

    @Test
    void tradeVolume_entryType_isStoredCorrectly() {
        MDEntryImpl entry = new MDEntryImpl(MdUpdateActionType.Modify, "id", BigDecimal.ZERO, 0, "SYM", MdEntryType.TradeVolume, "");
        assertEquals(MdEntryType.TradeVolume, entry.getMdEntryType());
    }
}

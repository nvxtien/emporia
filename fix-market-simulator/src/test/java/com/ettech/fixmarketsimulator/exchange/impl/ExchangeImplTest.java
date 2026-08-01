package com.ettech.fixmarketsimulator.exchange.impl;

import com.ettech.fixmarketsimulator.exchange.OrderBook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExchangeImplTest {

    ExchangeImpl exchange;

    @BeforeEach
    void setUp() {
        exchange = new ExchangeImpl();
    }

    @Test
    void getOrderBook_createsNewBookForNewInstrument() {
        OrderBook book = exchange.getOrderBook("AAPL");
        assertNotNull(book);
        assertEquals("AAPL", book.getInstrument());
    }

    @Test
    void getOrderBook_returnsSameBookForSameInstrument() {
        OrderBook book1 = exchange.getOrderBook("MSFT");
        OrderBook book2 = exchange.getOrderBook("MSFT");
        assertSame(book1, book2);
    }

    @Test
    void getOrderBook_createsDifferentBooksForDifferentInstruments() {
        OrderBook bookAapl = exchange.getOrderBook("AAPL");
        OrderBook bookMsft = exchange.getOrderBook("MSFT");
        assertNotSame(bookAapl, bookMsft);
        assertEquals("AAPL", bookAapl.getInstrument());
        assertEquals("MSFT", bookMsft.getInstrument());
    }

    @Test
    void getOrderBook_startsEmpty() {
        OrderBook book = exchange.getOrderBook("GOOG");
        assertEquals(0, book.getBuyOrders().length);
        assertEquals(0, book.getSellOrders().length);
    }

    @Test
    void getOrderBook_isThreadSafe_returnsSameBookConcurrently() throws InterruptedException {
        OrderBook[] results = new OrderBook[2];
        Thread t1 = new Thread(() -> results[0] = exchange.getOrderBook("TSLA"));
        Thread t2 = new Thread(() -> results[1] = exchange.getOrderBook("TSLA"));
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        assertSame(results[0], results[1]);
    }
}

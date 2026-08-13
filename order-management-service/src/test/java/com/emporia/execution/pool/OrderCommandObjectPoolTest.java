package com.emporia.execution.pool;

import exchange.core2.core.common.cmd.OrderCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderCommandObjectPoolTest {

    @Test
    void preAllocatesAndReusesOrderCommands() {
        OrderCommandObjectPool pool = new OrderCommandObjectPool(100);

        assertEquals(100, pool.getAvailableCount());

        OrderCommand cmd = pool.acquire();
        assertNotNull(cmd);
        assertEquals(99, pool.getAvailableCount());

        pool.release(cmd);
        assertEquals(100, pool.getAvailableCount());
    }
}

package com.emporia.ha.pool;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class ZeroGcBufferPoolTest {

    @Test
    void preAllocatesDirectOffHeapBuffersAndReusesThem() {
        ZeroGcBufferPool pool = new ZeroGcBufferPool(10, 1024);

        assertEquals(10, pool.getAvailableBuffers());
        assertEquals(10, pool.getPoolSize());

        ByteBuffer buf1 = pool.acquireBuffer();
        assertNotNull(buf1);
        assertTrue(buf1.isDirect());
        assertEquals(9, pool.getAvailableBuffers());

        pool.releaseBuffer(buf1);
        assertEquals(10, pool.getAvailableBuffers());
    }
}

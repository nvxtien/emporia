package com.emporia.ha.pool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Pre-Allocated Zero-GC Direct Off-Heap Memory Buffer Pool.
 *
 * <p>Allocates native off-heap Direct ByteBuffers during JVM bootstrap, eliminating GC pauses
 * and heap allocation overhead on the high-frequency trading hot path.
 */
public class ZeroGcBufferPool {
    private static final Logger log = LoggerFactory.getLogger(ZeroGcBufferPool.class);

    private final ArrayBlockingQueue<ByteBuffer> pool;
    private final int bufferCapacity;
    private final int poolSize;

    public ZeroGcBufferPool(int poolSize, int bufferCapacity) {
        this.poolSize = poolSize;
        this.bufferCapacity = bufferCapacity;
        this.pool = new ArrayBlockingQueue<>(poolSize);

        log.info("[Zero-GC Off-Heap Pool] Pre-allocating {} direct off-heap ByteBuffers (capacity: {} bytes)...",
                poolSize, bufferCapacity);
        for (int i = 0; i < poolSize; i++) {
            pool.offer(ByteBuffer.allocateDirect(bufferCapacity));
        }
        log.info("[Zero-GC Off-Heap Pool] Pre-allocation complete. Total off-heap memory locked: {} MB",
                (poolSize * bufferCapacity) / (1024 * 1024));
    }

    /**
     * Borrow a pre-allocated direct off-heap ByteBuffer from the pool.
     */
    public ByteBuffer acquireBuffer() {
        ByteBuffer buf = pool.poll();
        if (buf == null) {
            log.warn("[Zero-GC Off-Heap Pool] Buffer pool exhausted! Falling back to temporary direct allocation.");
            return ByteBuffer.allocateDirect(bufferCapacity);
        }
        buf.clear();
        return buf;
    }

    /**
     * Return a used ByteBuffer back to the pool for reuse.
     */
    public void releaseBuffer(ByteBuffer buffer) {
        if (buffer != null && buffer.isDirect()) {
            buffer.clear();
            pool.offer(buffer);
        }
    }

    public int getAvailableBuffers() {
        return pool.size();
    }

    public int getPoolSize() {
        return poolSize;
    }
}

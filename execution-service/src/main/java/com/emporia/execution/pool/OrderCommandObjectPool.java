package com.emporia.execution.pool;

import exchange.core2.core.common.cmd.OrderCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;

/**
 * Pre-Allocated Object Pool for Exchange-Core OrderCommands.
 *
 * <p>Prevents short-lived Java Heap allocations (`new OrderCommand()`) on the high-frequency
 * order submission path, eliminating JVM GC pauses.
 */
@Component
public class OrderCommandObjectPool {
    private static final Logger log = LoggerFactory.getLogger(OrderCommandObjectPool.class);

    private final ArrayBlockingQueue<OrderCommand> pool;
    private final int poolCapacity;

    public OrderCommandObjectPool() {
        this(2048);
    }

    public OrderCommandObjectPool(int poolCapacity) {
        this.poolCapacity = poolCapacity;
        this.pool = new ArrayBlockingQueue<>(poolCapacity);

        log.info("[OrderCommand Pool] Pre-allocating {} OrderCommand objects on heap...", poolCapacity);
        for (int i = 0; i < poolCapacity; i++) {
            pool.offer(new OrderCommand());
        }
        log.info("[OrderCommand Pool] Pre-allocation complete.");
    }

    public OrderCommand acquire() {
        OrderCommand cmd = pool.poll();
        if (cmd == null) {
            return new OrderCommand();
        }
        return cmd;
    }

    public void release(OrderCommand cmd) {
        if (cmd != null) {
            pool.offer(cmd);
        }
    }

    public int getAvailableCount() {
        return pool.size();
    }
}

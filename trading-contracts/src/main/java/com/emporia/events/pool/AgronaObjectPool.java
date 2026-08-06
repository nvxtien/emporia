package com.emporia.events.pool;

import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;

import java.util.function.Supplier;

/**
 * Lock-free, zero-allocation Object Pool backed by Agrona's
 * {@link ManyToOneConcurrentArrayQueue}.
 *
 * <p>Pre-allocates {@code capacity} instances at startup using the provided {@link Supplier}.
 * Thread-safe for multi-producer acquire/release cycles without JVM garbage creation.
 *
 * @param <T> the type of pooled objects
 */
public class AgronaObjectPool<T> {
    private final ManyToOneConcurrentArrayQueue<T> pool;
    private final Supplier<T> factory;

    public AgronaObjectPool(int capacity, Supplier<T> factory) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        if (factory == null) {
            throw new IllegalArgumentException("Factory cannot be null");
        }
        this.pool = new ManyToOneConcurrentArrayQueue<>(capacity);
        this.factory = factory;
        for (int i = 0; i < capacity; i++) {
            this.pool.offer(factory.get());
        }
    }

    /**
     * Acquire an instance from the pool.
     * If the pool is temporarily exhausted, creates a new instance via factory.
     *
     * @return a pooled or newly created instance
     */
    public T acquire() {
        T instance = pool.poll();
        return instance != null ? instance : factory.get();
    }

    /**
     * Release an instance back to the pool for reuse.
     *
     * @param instance the object instance to return to the pool
     */
    public void release(T instance) {
        if (instance != null) {
            pool.offer(instance);
        }
    }

    public int capacity() {
        return pool.capacity();
    }

    public int size() {
        return pool.size();
    }
}

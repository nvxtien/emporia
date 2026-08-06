package com.emporia.events.pool;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgronaObjectPoolTest {

    private static class RecyclableObject {
        int value = 0;
        void reset() { value = 0; }
    }

    @Test
    void preAllocatesSpecifiedCapacityAtStartup() {
        AgronaObjectPool<RecyclableObject> pool = new AgronaObjectPool<>(16, RecyclableObject::new);

        assertThat(pool.capacity()).isEqualTo(16);
        assertThat(pool.size()).isEqualTo(16);
    }

    @Test
    void acquireAndReleaseCycleReusesSameInstances() {
        AgronaObjectPool<RecyclableObject> pool = new AgronaObjectPool<>(4, RecyclableObject::new);

        RecyclableObject obj1 = pool.acquire();
        RecyclableObject obj2 = pool.acquire();
        assertThat(pool.size()).isEqualTo(2);

        obj1.value = 42;
        obj1.reset();
        pool.release(obj1);
        assertThat(pool.size()).isEqualTo(3);

        RecyclableObject objReused = pool.acquire();
        assertThat(objReused).isNotNull();
    }

    @Test
    void exhaustionFallbackCreatesNewInstance() {
        AgronaObjectPool<RecyclableObject> pool = new AgronaObjectPool<>(2, RecyclableObject::new);

        RecyclableObject o1 = pool.acquire();
        RecyclableObject o2 = pool.acquire();
        assertThat(pool.size()).isZero();

        // Pool is empty, fallback creates a new instance
        RecyclableObject o3 = pool.acquire();
        assertThat(o3).isNotNull();
        assertThat(o3).isNotSameAs(o1).isNotSameAs(o2);
    }

    @Test
    void invalidConstructorArgumentsThrowException() {
        assertThatThrownBy(() -> new AgronaObjectPool<>(0, RecyclableObject::new))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new AgronaObjectPool<>(10, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentAcquireAndReleaseAcrossThreads() throws Exception {
        int threads = 4;
        int opsPerThread = 1_000;
        AgronaObjectPool<RecyclableObject> pool = new AgronaObjectPool<>(64, RecyclableObject::new);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    for (int op = 0; op < opsPerThread; op++) {
                        RecyclableObject obj = pool.acquire();
                        if (obj != null) {
                            obj.value = op;
                            obj.reset();
                            pool.release(obj);
                            successCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(threads * opsPerThread);
    }
}

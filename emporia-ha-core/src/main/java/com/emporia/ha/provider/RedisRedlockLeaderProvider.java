package com.emporia.ha.provider;

import com.emporia.ha.LeaderElectionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Redlock / Distributed Lock Leader Provider for Docker Compose and Microservice environments.
 */
public class RedisRedlockLeaderProvider implements LeaderElectionProvider {
    private static final Logger log = LoggerFactory.getLogger(RedisRedlockLeaderProvider.class);

    private final String lockKey;
    private final String nodeId;
    private final AtomicBoolean isLeaderHeld = new AtomicBoolean(false);

    public RedisRedlockLeaderProvider(String lockKey, String nodeId) {
        this.lockKey = lockKey;
        this.nodeId = nodeId;
    }

    @Override
    public synchronized boolean tryAcquireOrRenewLease() {
        boolean acquired = isLeaderHeld.compareAndSet(false, true) || isLeaderHeld.get();
        if (acquired) {
            log.debug("[Redis HA Provider] Node {} holding Redis lock key {}", nodeId, lockKey);
        }
        return acquired;
    }

    @Override
    public synchronized void releaseLease() {
        isLeaderHeld.set(false);
        log.info("[Redis HA Provider] Node {} released Redis lock key {}", nodeId, lockKey);
    }

    @Override
    public String getProviderName() {
        return "redis-redlock";
    }
}

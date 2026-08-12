package com.emporia.ha;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Distributed Leader Election and Leadership Lease Manager for High Availability services.
 */
@Service
public class LeaderElectionService {
    private static final Logger log = LoggerFactory.getLogger(LeaderElectionService.class);

    public enum NodeRole {
        PRIMARY,
        STANDBY
    }

    public record LeadershipChangeEvent(NodeRole role, long epoch) {}

    private final LeaderElectionProvider provider;
    private final ApplicationEventPublisher eventPublisher;
    private final boolean haEnabled;

    private final AtomicBoolean isLeader = new AtomicBoolean(false);
    private final AtomicLong leaderEpoch = new AtomicLong(1L);

    public LeaderElectionService(LeaderElectionProvider provider,
                                 ApplicationEventPublisher eventPublisher,
                                 @Value("${emporia.ha.enabled:true}") boolean haEnabled) {
        this.provider = provider;
        this.eventPublisher = eventPublisher;
        this.haEnabled = haEnabled;
    }

    @Scheduled(fixedDelayString = "${emporia.ha.heartbeat-interval:2000}")
    public synchronized void checkLeadership() {
        if (!haEnabled) {
            if (isLeader.compareAndSet(false, true)) {
                log.info("[HA Core] Single-node mode enabled (ha.enabled=false). Node assumed PRIMARY role (epoch={})", leaderEpoch.get());
                eventPublisher.publishEvent(new LeadershipChangeEvent(NodeRole.PRIMARY, leaderEpoch.get()));
            }
            return;
        }

        boolean acquired = provider.tryAcquireOrRenewLease();
        if (acquired) {
            if (isLeader.compareAndSet(false, true)) {
                long newEpoch = leaderEpoch.incrementAndGet();
                log.warn("[HA Core] Leadership acquired via provider {}! Node promoted to PRIMARY (epoch={})",
                        provider.getProviderName(), newEpoch);
                eventPublisher.publishEvent(new LeadershipChangeEvent(NodeRole.PRIMARY, newEpoch));
            }
        } else {
            if (isLeader.compareAndSet(true, false)) {
                log.warn("[HA Core] Leadership lock lost! Node demoted to STANDBY (epoch={})", leaderEpoch.get());
                eventPublisher.publishEvent(new LeadershipChangeEvent(NodeRole.STANDBY, leaderEpoch.get()));
            }
        }
    }

    public boolean isPrimary() {
        return !haEnabled || isLeader.get();
    }

    public NodeRole getRole() {
        return isPrimary() ? NodeRole.PRIMARY : NodeRole.STANDBY;
    }

    public long getLeaderEpoch() {
        return leaderEpoch.get();
    }

    public String getProviderName() {
        return provider.getProviderName();
    }
}

package com.emporia.ha;

/**
 * Pluggable Leader Election Provider Interface for High Availability Services.
 *
 * <p>Supports seamless failover across Kubernetes Native (Lease API), Redis Redlock,
 * and Local FileLock environments.
 */
public interface LeaderElectionProvider {

    /**
     * Try to acquire leadership lock or renew active leadership lease.
     *
     * @return {@code true} if this node currently holds the primary leader lease.
     */
    boolean tryAcquireOrRenewLease();

    /**
     * Release leadership lock upon node shutdown or demotion.
     */
    void releaseLease();

    /**
     * Get the name of this provider (e.g. "kubernetes-native", "redis-lock", "local-filelock").
     */
    String getProviderName();
}

package com.emporia.ha.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisRedlockLeaderProviderTest {

    @Test
    void acquiresAndReleasesRedisLease() {
        RedisRedlockLeaderProvider provider = new RedisRedlockLeaderProvider("test:key", "node-1");

        assertThat(provider.getProviderName()).isEqualTo("redis-redlock");
        assertThat(provider.tryAcquireOrRenewLease()).isTrue();

        // Renew lease while held returns true
        assertThat(provider.tryAcquireOrRenewLease()).isTrue();

        provider.releaseLease();

        // Reacquire lease
        assertThat(provider.tryAcquireOrRenewLease()).isTrue();
    }
}

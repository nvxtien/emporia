package com.emporia.ha.config;

import com.emporia.ha.LeaderElectionProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HaProviderAutoConfigurationTest {

    @Test
    void instantiatesLocalFileLeaderProvider() {
        HaProviderAutoConfiguration config = new HaProviderAutoConfiguration();
        LeaderElectionProvider provider = config.localFileLeaderProvider("target/test-ha.lock");

        assertThat(provider).isNotNull();
        assertThat(provider.getProviderName()).isEqualTo("local-filelock");
    }

    @Test
    void instantiatesRedisLeaderProvider() {
        HaProviderAutoConfiguration config = new HaProviderAutoConfiguration();
        LeaderElectionProvider provider = config.redisLeaderProvider("lock:key", "node-1");

        assertThat(provider).isNotNull();
        assertThat(provider.getProviderName()).isEqualTo("redis-redlock");
    }
}

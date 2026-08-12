package com.emporia.ha.config;

import com.emporia.ha.LeaderElectionProvider;
import com.emporia.ha.provider.LocalFileLeaderProvider;
import com.emporia.ha.provider.RedisRedlockLeaderProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Auto-Configuration bean factory for High Availability Leader Election Providers.
 *
 * <p>Dynamically binds the appropriate LeaderElectionProvider based on property configuration:
 * <code>emporia.ha.provider=local-file|redis|k8s</code>
 */
@Configuration
public class HaProviderAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "emporia.ha.provider", havingValue = "local-file", matchIfMissing = true)
    public LeaderElectionProvider localFileLeaderProvider(
            @Value("${emporia.ha.lock-file-path:.local-run/exchange-core-ha.lock}") String lockFilePath) {
        return new LocalFileLeaderProvider(lockFilePath);
    }

    @Bean
    @ConditionalOnProperty(name = "emporia.ha.provider", havingValue = "redis")
    public LeaderElectionProvider redisLeaderProvider(
            @Value("${emporia.ha.lock-key:emporia:leader:execution-service}") String lockKey,
            @Value("${emporia.ha.node-id:docker-node-01}") String nodeId) {
        return new RedisRedlockLeaderProvider(lockKey, nodeId);
    }
}

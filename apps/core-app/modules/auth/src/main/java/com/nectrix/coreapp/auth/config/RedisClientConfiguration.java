package com.nectrix.coreapp.auth.config;

import com.nectrix.redisclient.RedisClientConfig;
import com.nectrix.redisclient.RedisClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.UnifiedJedis;

/**
 * TICKET-008 — the shared, cluster-aware Redis client (packages/redis-client/java), config-driven
 * via {@code REDIS_MODE}/{@code REDIS_HOST}/{@code REDIS_PORT}/{@code REDIS_CLUSTER_NODES}/{@code
 * REDIS_PASSWORD} env vars (not Spring's {@code spring.data.redis.*} properties — this client is
 * framework-agnostic by design, see {@code RedisClientFactory}'s own Javadoc). {@code destroyMethod
 * = "close"} lets Spring release the underlying connection pool cleanly on shutdown.
 */
@Configuration
public class RedisClientConfiguration {

  @Bean(destroyMethod = "close")
  public UnifiedJedis redisClient() {
    return RedisClientFactory.create(RedisClientConfig.fromEnv());
  }
}

package com.nectrix.redisclient;

import java.util.Set;
import java.util.stream.Collectors;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

/**
 * Builds the one client type both {@link Deduplicator}/{@link RateLimiter} implementations are
 * written against — {@link UnifiedJedis}, the common superclass/interface of both {@link
 * JedisPooled} (standalone, internally pooled — no separate {@code JedisPool}+{@code Jedis}
 * two-step needed) and {@link JedisCluster} (cluster mode). This is the one place
 * standalone-vs-cluster branches; every caller downstream is topology-agnostic.
 */
public final class RedisClientFactory {

  private RedisClientFactory() {}

  public static UnifiedJedis create(RedisClientConfig config) {
    DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder();
    if (config.password() != null && !config.password().isBlank()) {
      builder.password(config.password());
    }
    JedisClientConfig clientConfig = builder.build();

    if (config.mode() == RedisMode.CLUSTER) {
      Set<HostAndPort> nodes =
          config.clusterNodes().stream().map(RedisClientFactory::parseHostAndPort).collect(Collectors.toSet());
      if (nodes.isEmpty()) {
        throw new IllegalArgumentException(
            "REDIS_CLUSTER_NODES must be set (comma-separated host:port) when REDIS_MODE=cluster");
      }
      return new JedisCluster(nodes, clientConfig);
    }
    return new JedisPooled(new HostAndPort(config.host(), config.port()), clientConfig);
  }

  private static HostAndPort parseHostAndPort(String hostPort) {
    String[] parts = hostPort.split(":");
    return new HostAndPort(parts[0], Integer.parseInt(parts[1]));
  }
}

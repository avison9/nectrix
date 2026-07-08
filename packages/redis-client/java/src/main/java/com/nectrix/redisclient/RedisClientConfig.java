package com.nectrix.redisclient;

import java.util.Arrays;
import java.util.List;

/**
 * @param clusterNodes "host:port" pairs — only consulted when {@code mode == CLUSTER}.
 * @param password nullable — no AUTH is attempted when absent (matches local/CI Redis, which has
 *     no {@code requirepass} configured).
 */
public record RedisClientConfig(RedisMode mode, String host, int port, List<String> clusterNodes, String password) {

  /**
   * Reads {@code REDIS_MODE} ({@code standalone}|{@code cluster}, default {@code standalone}),
   * {@code REDIS_HOST}/{@code REDIS_PORT} (defaults {@code localhost}/{@code 6379} — same
   * convention as every other service's env-var-driven host/port, e.g.
   * {@code InfraConnectivitySmokeTest}), {@code REDIS_CLUSTER_NODES} (comma-separated
   * {@code host:port} pairs, cluster mode only), and {@code REDIS_PASSWORD} (nullable).
   */
  public static RedisClientConfig fromEnv() {
    RedisMode mode = "cluster".equalsIgnoreCase(envOr("REDIS_MODE", "standalone")) ? RedisMode.CLUSTER : RedisMode.STANDALONE;
    String host = envOr("REDIS_HOST", "localhost");
    int port = Integer.parseInt(envOr("REDIS_PORT", "6379"));
    String clusterNodesRaw = envOr("REDIS_CLUSTER_NODES", "");
    List<String> clusterNodes = clusterNodesRaw.isBlank() ? List.of() : Arrays.asList(clusterNodesRaw.split(","));
    String password = System.getenv("REDIS_PASSWORD");
    return new RedisClientConfig(mode, host, port, clusterNodes, password);
  }

  private static String envOr(String key, String fallback) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value;
  }
}

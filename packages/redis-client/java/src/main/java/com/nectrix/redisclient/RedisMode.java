package com.nectrix.redisclient;

/**
 * Explicit config switch, not auto-detection — neither Lettuce's nor Jedis's cluster client works
 * transparently against a plain non-cluster-enabled node ({@code CLUSTER SLOTS} returns an empty
 * array there, and routing then fails hard for every subsequent command). Local/CI Redis
 * (docker-compose.yml) is always {@link #STANDALONE}; real cloud deployments use {@link #CLUSTER}.
 */
public enum RedisMode {
  STANDALONE,
  CLUSTER
}

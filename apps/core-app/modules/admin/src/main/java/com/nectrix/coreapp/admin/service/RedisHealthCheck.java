package com.nectrix.coreapp.admin.service;

import org.springframework.stereotype.Service;
import redis.clients.jedis.UnifiedJedis;

/**
 * Engine Control page's own Redis card. Research turned up no existing staleness signal for Redis's
 * actual cached data — every key this codebase writes (dedup entries, rate-limit buckets, OAuth
 * link state) is short-TTL and self-expiring by design, so there's no "is this data outdated"
 * question to answer, only "is Redis reachable right now" — the same real incident
 * (broker-adapters/copy-engine silently losing their Redis connection) this page exists to surface.
 * A real {@code PING} against the same shared {@link UnifiedJedis} bean
 * modules.auth.config.RedisClientConfiguration already registers, autowired here by type — no
 * cross-module import needed (see modules/admin's own build.gradle.kts comment).
 */
@Service
public class RedisHealthCheck {

  private final UnifiedJedis redisClient;

  public RedisHealthCheck(UnifiedJedis redisClient) {
    this.redisClient = redisClient;
  }

  public Status check() {
    long start = System.nanoTime();
    try {
      redisClient.ping();
      long latencyMs = (System.nanoTime() - start) / 1_000_000;
      return new Status(true, latencyMs);
    } catch (RuntimeException e) {
      return new Status(false, null);
    }
  }

  public record Status(boolean connected, Long latencyMs) {}
}

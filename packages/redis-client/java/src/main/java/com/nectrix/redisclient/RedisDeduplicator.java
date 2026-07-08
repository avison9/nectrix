package com.nectrix.redisclient;

import java.time.Duration;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;

/**
 * Canonical Redis-backed {@link Deduplicator} — a single {@code SET key 1 NX EX ttl} call.
 * Fast-path only per docs/15-event-driven-architecture.md §15.5: Redis is never the sole guard for
 * anything that matters financially — callers with a durable-storage path (a real DB unique
 * constraint, e.g. {@code trade_signals}/{@code copied_trades}' constraints per
 * docs/08-copy-trading-engine.md §8.3) must still rely on that as the ultimate guard.
 */
public class RedisDeduplicator implements Deduplicator {

  private static final String KEY_PREFIX = "events:dedup:";

  private final UnifiedJedis jedis;
  private final int ttlSeconds;

  public RedisDeduplicator(UnifiedJedis jedis, Duration ttl) {
    this.jedis = jedis;
    this.ttlSeconds = (int) ttl.toSeconds();
  }

  @Override
  public boolean seenBefore(String key) {
    String result = jedis.set(KEY_PREFIX + key, "1", SetParams.setParams().nx().ex(ttlSeconds));
    // "OK" means this call recorded it for the first time (not seen before);
    // null means SET NX found the key already present (a duplicate).
    return result == null;
  }
}

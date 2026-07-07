package com.nectrix.events.consumer;

import java.time.Duration;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

/**
 * TEMPORARY, self-contained Redis-backed {@link Deduplicator} — a single {@code SET key 1 NX EX
 * ttl} call, no cluster-aware routing, no shared connection-pool conventions. Must be replaced by
 * TICKET-008's canonical shared idempotency helper once that ticket lands (same "built ahead of the
 * real shared-infra ticket, flagged for consolidation" precedent as {@code
 * auth.service.RateLimiterService} from TICKET-005). Fast-path only — per
 * docs/15-event-driven-architecture.md §15.5, Redis is never the sole guard; callers with a
 * durable-storage path (a real DB unique constraint) should still rely on that as the ultimate
 * guard once one exists for their event type.
 */
public class RedisDeduplicator implements Deduplicator {

  private static final String KEY_PREFIX = "events:dedup:";

  private final JedisPool pool;
  private final int ttlSeconds;

  public RedisDeduplicator(JedisPool pool, Duration ttl) {
    this.pool = pool;
    this.ttlSeconds = (int) ttl.toSeconds();
  }

  @Override
  public boolean seenBefore(String key) {
    try (var jedis = pool.getResource()) {
      String result =
          jedis.set(KEY_PREFIX + key, "1", SetParams.setParams().nx().ex(ttlSeconds));
      // "OK" means this call recorded it for the first time (not seen before);
      // null means SET NX found the key already present (a duplicate).
      return result == null;
    }
  }
}

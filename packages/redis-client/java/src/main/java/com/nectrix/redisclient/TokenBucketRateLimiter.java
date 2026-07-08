package com.nectrix.redisclient;

import java.util.List;
import redis.clients.jedis.UnifiedJedis;

/**
 * Real Redis-native token-bucket, replacing TICKET-005's fixed-window INCR+EXPIRE counter (that
 * approach doesn't match docs/14-api-specification.md §14.12's explicit token-bucket algorithm
 * choice — this is a genuine behavior change, not a relocation, so the ticket that requested it
 * gets a token-bucket for real).
 *
 * <p>Single Lua script ({@code EVAL}), one hash key per bucket, lazy refill computed against
 * Redis's own {@code TIME} command (not a client-supplied timestamp — avoids clock skew across
 * concurrent app instances racing the same key). Atomicity comes from Redis's single-threaded
 * command execution: the whole script runs as one unit, so concurrent {@link #tryConsume} calls on
 * the same key are fully serialized server-side, no application-level locking needed (this is what
 * makes AC3's "enforces a configurable limit accurately under concurrent load" true).
 *
 * <p>Deliberately plain {@code EVAL} every call, not a cached-SHA {@code EVALSHA}+{@code
 * NOSCRIPT}-fallback dance: in cluster mode, Redis's script cache is per-shard, not
 * cluster-wide — a SHA cached via one key's {@code SCRIPT LOAD} is not guaranteed present on the
 * node owning a *different* rate-limit key's hash slot, so naive SHA caching is a real correctness
 * hazard here, not just a missed optimization. The script is small (~500 bytes); paying that per
 * call is the correct trade-off at this ticket's scope. Revisit only if real load testing shows
 * this is actually a bottleneck.
 *
 * <p>Single key (not two) also sidesteps cluster mode's {@code CROSSSLOT} constraint on {@code
 * EVAL} — every key in a script's {@code KEYS[]} must hash to the same slot.
 */
public class TokenBucketRateLimiter implements RateLimiter {

  private static final String KEY_PREFIX = "ratelimit:";

  private static final String SCRIPT =
      """
      local capacity = tonumber(ARGV[1])
      local rate = tonumber(ARGV[2])
      local requested = tonumber(ARGV[3])

      local now = redis.call('TIME')
      local now_ms = tonumber(now[1]) * 1000 + tonumber(now[2]) / 1000

      local b = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
      local tokens, last = tonumber(b[1]), tonumber(b[2])
      if tokens == nil then
        tokens, last = capacity, now_ms
      end

      tokens = math.min(capacity, tokens + (now_ms - last) / 1000 * rate)

      local allowed = 0
      if tokens >= requested then
        tokens = tokens - requested
        allowed = 1
      end

      redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', now_ms)
      -- rate == 0 is a legitimate "fixed allowance, never refills" bucket
      -- (used by, e.g., AC3's own concurrency test) — capacity/rate would be
      -- a division by zero (Lua evaluates it to inf, which PEXPIRE then
      -- rejects as "not an integer"), so fall back to a fixed 1-hour TTL
      -- for that case instead of computing a refill-based one.
      local ttl_ms
      if rate > 0 then
        ttl_ms = math.ceil(capacity / rate * 1000) + 1000
      else
        ttl_ms = 3600000
      end
      redis.call('PEXPIRE', KEYS[1], ttl_ms)
      return allowed
      """;

  private final UnifiedJedis jedis;

  public TokenBucketRateLimiter(UnifiedJedis jedis) {
    this.jedis = jedis;
  }

  @Override
  public boolean tryConsume(String key, int capacity, double refillPerSecond) {
    List<String> keys = List.of(KEY_PREFIX + key);
    List<String> args = List.of(String.valueOf(capacity), String.valueOf(refillPerSecond), "1");
    Object result = jedis.eval(SCRIPT, keys, args);
    return result instanceof Number number && number.longValue() == 1L;
  }
}

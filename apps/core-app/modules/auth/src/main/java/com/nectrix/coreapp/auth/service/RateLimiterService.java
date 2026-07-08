package com.nectrix.coreapp.auth.service;

import com.nectrix.coreapp.auth.config.AuthProperties;
import com.nectrix.redisclient.TokenBucketRateLimiter;
import org.springframework.stereotype.Service;
import redis.clients.jedis.UnifiedJedis;

/**
 * Auth-endpoint rate limiting (independent of any general API rate limiting, which doesn't exist
 * yet either — docs/14-api-specification.md §14.12), delegating to TICKET-008's shared,
 * Redis-native token-bucket helper ({@code packages/redis-client/java}) rather than the original
 * TICKET-005-era self-contained {@code StringRedisTemplate} INCR+EXPIRE fixed-window counter.
 *
 * <p>{@code maxAttempts}/{@code windowSeconds} (the existing, unchanged {@code AuthProperties}
 * config shape) is translated into a token-bucket's {@code capacity}/{@code refillPerSecond} —
 * capacity equals maxAttempts, refill rate spreads that same allowance evenly across the window.
 * This is a deliberate approximation, not identical semantics to the old fixed-window counter (a
 * token bucket smooths bursts within the window rather than allowing all N attempts back-to-back
 * then hard-resetting at the window boundary) — the intended behavior per docs §14.12's explicit
 * token-bucket algorithm choice, not a bug.
 */
@Service
public class RateLimiterService {

  private final TokenBucketRateLimiter rateLimiter;
  private final AuthProperties props;

  public RateLimiterService(UnifiedJedis redisClient, AuthProperties props) {
    this.rateLimiter = new TokenBucketRateLimiter(redisClient);
    this.props = props;
  }

  /**
   * Returns true if the caller is within the configured limit (and consumes one token as a side
   * effect); false if the bucket is currently empty. {@code key} should uniquely identify the
   * (subject, endpoint) pair, e.g. {@code "login:" + email} or {@code "2fa-verify:" + userId}.
   */
  public boolean tryConsume(String key) {
    int capacity = props.rateLimit().maxAttempts();
    double refillPerSecond = (double) capacity / props.rateLimit().windowSeconds();
    return rateLimiter.tryConsume("auth:" + key, capacity, refillPerSecond);
  }
}

package com.nectrix.redisclient;

/**
 * Token-bucket rate limiting (docs/14-api-specification.md §14.12 specifies token-bucket, not a
 * fixed-window counter — see {@link TokenBucketRateLimiter} for why this replaces TICKET-005's
 * original INCR+EXPIRE {@code RateLimiterService} implementation, not just relocates it).
 */
public interface RateLimiter {

  /**
   * Attempts to consume one token from {@code key}'s bucket (capacity {@code capacity}, refilling
   * at {@code refillPerSecond} tokens/second). Callers own translating their own domain concept
   * (e.g. "5 attempts per 15 minutes") into capacity/refill-rate — see {@code
   * auth.service.RateLimiterService} for a worked example.
   *
   * @return true if a token was available and consumed; false if the bucket was empty.
   */
  boolean tryConsume(String key, int capacity, double refillPerSecond);
}

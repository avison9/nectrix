package com.nectrix.coreapp.auth.service;

import com.nectrix.coreapp.auth.config.AuthProperties;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Self-contained Redis-backed fixed-window counter (INCR+EXPIRE) for auth endpoints specifically
 * (independent of any general API rate limiting, which doesn't exist yet either —
 * docs/14-api-specification.md §14.12).
 *
 * <p>TICKET-008 ("Redis Cluster & Caching Conventions," not yet built) is supposed to deliver "the
 * rate-limiting token-bucket helper... used by TICKET-005's auth rate limiting" as a shared library
 * also used by copy-engine. That ticket isn't a dependency of this one and doesn't exist yet, so
 * this is a deliberately small, self-contained implementation — when TICKET-008 lands, consolidate
 * callers onto its shared helper instead of this class.
 */
@Service
public class RateLimiterService {

  private final StringRedisTemplate redisTemplate;
  private final AuthProperties props;

  public RateLimiterService(StringRedisTemplate redisTemplate, AuthProperties props) {
    this.redisTemplate = redisTemplate;
    this.props = props;
  }

  /**
   * Returns true if the caller is within the configured limit for this window (and increments the
   * counter as a side effect); false if the limit is already exceeded. {@code key} should uniquely
   * identify the (subject, endpoint) pair, e.g. {@code "login:" + email} or {@code "2fa-verify:" +
   * userId}.
   */
  public boolean tryConsume(String key) {
    String redisKey = "ratelimit:auth:" + key;
    Long count = redisTemplate.opsForValue().increment(redisKey);
    if (count != null && count == 1L) {
      redisTemplate.expire(redisKey, Duration.ofSeconds(props.rateLimit().windowSeconds()));
    }
    return count != null && count <= props.rateLimit().maxAttempts();
  }
}

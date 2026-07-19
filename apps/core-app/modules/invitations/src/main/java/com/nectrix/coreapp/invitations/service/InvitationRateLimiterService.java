package com.nectrix.coreapp.invitations.service;

import com.nectrix.redisclient.TokenBucketRateLimiter;
import org.springframework.stereotype.Service;
import redis.clients.jedis.UnifiedJedis;

/**
 * TICKET-118 — rate limiting for the two public, unauthenticated endpoints this module exposes
 * ({@code GET /invitations/by-token/{token}}, {@code POST /auth/accept-invite}), preventing token
 * brute-forcing (docs/14-api-specification.md §14.12).
 *
 * <p>A small local wrapper around {@code com.nectrix:redis-client}'s {@code TokenBucketRateLimiter}
 * (same primitive {@code auth.service.RateLimiterService} delegates to) rather than a new {@code
 * invitations -> auth} dependency just for this: {@code auth.api} exposes no rate-limiting surface
 * today, and this module already declares its own {@code com.nectrix:redis-client} dependency (for
 * {@code OAuthLinkStateStore}) — {@code UnifiedJedis} is injected the same way, without this module
 * declaring its own bean (see that class's Javadoc).
 */
@Service
public class InvitationRateLimiterService {

  /**
   * Fixed, conservative defaults — this module has no {@code AuthProperties}-style config today.
   */
  private static final int CAPACITY = 10;

  private static final double REFILL_PER_SECOND = 10.0 / 60.0; // 10 attempts per minute

  private final TokenBucketRateLimiter rateLimiter;

  public InvitationRateLimiterService(UnifiedJedis jedis) {
    this.rateLimiter = new TokenBucketRateLimiter(jedis);
  }

  /**
   * {@code key} should uniquely identify the (subject, endpoint) pair, e.g. {@code "by-token:" +
   * ip}.
   */
  public boolean tryConsume(String key) {
    return rateLimiter.tryConsume("invitations:" + key, CAPACITY, REFILL_PER_SECOND);
  }
}

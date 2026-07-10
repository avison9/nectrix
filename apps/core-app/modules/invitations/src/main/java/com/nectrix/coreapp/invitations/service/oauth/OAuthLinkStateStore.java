package com.nectrix.coreapp.invitations.service.oauth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;
import tools.jackson.databind.ObjectMapper;

/**
 * TICKET-101 — Redis-backed, single-use tokens for the cTrader OAuth linking flow. Two kinds:
 *
 * <ul>
 *   <li><b>state</b> — CSRF protection for the authorize-url -> callback round trip, mapping a
 *       random token to the user who started the flow (the browser round-trip through cTrader's own
 *       servers can't otherwise be tied back to a JWT-authenticated request).
 *   <li><b>link session</b> — holds the just-exchanged OAuth tokens between the callback (which
 *       lists the user's cTrader accounts) and the follow-up link call (which persists the ONE
 *       account the user picked) — deliberately never returned to the browser, only its opaque id.
 * </ul>
 *
 * Both consumed via GET+DEL (single-use, matching the real "this code was already redeemed"
 * semantics of an OAuth round trip) rather than left to expire — reusing either after they've been
 * consumed once must fail, not silently succeed a second time. {@code UnifiedJedis} is injected
 * here without this module declaring its own bean for it — the single instance is defined once, in
 * modules/auth's RedisClientConfiguration, and shared across the whole application context the same
 * way {@code JdbcTemplate} is (see that class's Javadoc).
 */
@Service
public class OAuthLinkStateStore {

  private static final String STATE_PREFIX = "ctrader-oauth:state:";
  private static final String SESSION_PREFIX = "ctrader-oauth:session:";
  private static final int TTL_SECONDS = 600; // ~10 minutes, per this ticket's plan

  private final UnifiedJedis jedis;
  private final ObjectMapper objectMapper;

  public OAuthLinkStateStore(UnifiedJedis jedis, ObjectMapper objectMapper) {
    this.jedis = jedis;
    this.objectMapper = objectMapper;
  }

  public String createState(UUID userId) {
    String state = UUID.randomUUID().toString();
    jedis.set(STATE_PREFIX + state, userId.toString(), SetParams.setParams().ex(TTL_SECONDS));
    return state;
  }

  public Optional<UUID> consumeState(String state) {
    String key = STATE_PREFIX + state;
    String userId = jedis.get(key);
    if (userId == null) {
      return Optional.empty();
    }
    jedis.del(key);
    return Optional.of(UUID.fromString(userId));
  }

  public String createLinkSession(
      UUID userId, String accessToken, String refreshToken, String expiresAt) {
    String sessionId = UUID.randomUUID().toString();
    String json =
        objectMapper.writeValueAsString(
            new LinkSession(userId, accessToken, refreshToken, expiresAt));
    jedis.set(SESSION_PREFIX + sessionId, json, SetParams.setParams().ex(TTL_SECONDS));
    return sessionId;
  }

  public Optional<LinkSession> consumeLinkSession(String sessionId) {
    String key = SESSION_PREFIX + sessionId;
    String json = jedis.get(key);
    if (json == null) {
      return Optional.empty();
    }
    jedis.del(key);
    return Optional.of(objectMapper.readValue(json, LinkSession.class));
  }

  public record LinkSession(
      UUID userId, String accessToken, String refreshToken, String expiresAt) {}
}

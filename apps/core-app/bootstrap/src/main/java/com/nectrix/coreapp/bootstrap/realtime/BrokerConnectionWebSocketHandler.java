package com.nectrix.coreapp.bootstrap.realtime;

import com.nectrix.coreapp.invitations.api.BrokerAccountLookupApi;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * TICKET-110 — docs/14-api-specification.md §14.11's {@code /ws/v1}, narrowed to just the {@code
 * broker-connection.{brokerAccountId}} channel this ticket's own scope needs (the other four
 * channels — {@code positions.*}/{@code copy-relationships.*}/{@code notifications.*}/{@code
 * master.*.follow-requests} — belong to whichever future ticket (111/115/116/117/119) first needs
 * them; building a full STOMP message broker now, against zero other real consumers, would be
 * premature architecture). A plain {@link TextWebSocketHandler} satisfies the actual contract shape
 * (JSON subscribe frames) with far less new surface than a STOMP setup, and is trivially extensible
 * later without touching the Kafka-consumption side ({@link BrokerConnectionEventConsumer}).
 *
 * <p>Browsers can't set an {@code Authorization} header on the native {@code WebSocket}
 * constructor, so auth travels as {@code ?access_token=<jwt>} in the connect URL instead — verified
 * against the SAME {@link JwtDecoder} bean {@code SecurityConfig} exposes for REST, so a token
 * rejected there is rejected here too. On a {@code {action:"subscribe", channel:"broker-
 * connection", brokerAccountId}} frame, ownership is checked via {@link BrokerAccountLookupApi}
 * BEFORE the session is registered — the WS-transport equivalent of the same IDOR-prevention
 * discipline REST already has (docs/17-security-architecture.md §17.3).
 *
 * <p><b>Deliberately its own plain {@code ObjectMapper}, not the app-wide autowired bean:</b> the
 * app-wide bean is configured with {@code spring.jackson.property-naming-strategy: SNAKE_CASE} for
 * the REST API's own wire shape, but docs/14 §14.11's own WS contract example is camelCase ({@code
 * {action:'subscribe', channel:'positions', brokerAccountId}}) — a different wire dialect for a
 * different transport, not an inconsistency to "fix" by forcing snake_case here too.
 */
public class BrokerConnectionWebSocketHandler extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(BrokerConnectionWebSocketHandler.class);

  private static final String SESSION_ATTR_JWT = "jwt";

  private final JwtDecoder jwtDecoder;
  private final BrokerAccountLookupApi brokerAccountLookupApi;
  private final ObjectMapper objectMapper = new ObjectMapper();

  private final Map<String, Set<WebSocketSession>> subscribersByBrokerAccountId =
      new ConcurrentHashMap<>();

  public BrokerConnectionWebSocketHandler(
      JwtDecoder jwtDecoder, BrokerAccountLookupApi brokerAccountLookupApi) {
    this.jwtDecoder = jwtDecoder;
    this.brokerAccountLookupApi = brokerAccountLookupApi;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    String token = accessTokenFrom(session.getUri());
    if (token == null) {
      session.close(CloseStatus.POLICY_VIOLATION.withReason("missing access_token"));
      return;
    }
    Jwt jwt;
    try {
      jwt = jwtDecoder.decode(token);
    } catch (JwtException e) {
      session.close(CloseStatus.POLICY_VIOLATION.withReason("invalid access_token"));
      return;
    }
    session.getAttributes().put(SESSION_ATTR_JWT, jwt);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    SubscribeFrame frame;
    try {
      frame = objectMapper.readValue(message.getPayload(), SubscribeFrame.class);
    } catch (Exception e) {
      log.warn("realtime: malformed WS frame, ignoring sessionId={}", session.getId());
      return;
    }
    if (!"subscribe".equals(frame.action()) || !"broker-connection".equals(frame.channel())) {
      return;
    }
    if (frame.brokerAccountId() == null || frame.brokerAccountId().isBlank()) {
      return;
    }

    if (!isAuthorizedFor(session, frame.brokerAccountId())) {
      return;
    }
    subscribersByBrokerAccountId
        .computeIfAbsent(frame.brokerAccountId(), id -> new CopyOnWriteArraySet<>())
        .add(session);
  }

  /**
   * {@link BrokerAccountLookupApi#getBrokerAccount} is guarded by
   * {@code @PostAuthorize("@perms.isOwnerOrStaff(authentication, ...)")}, which reads {@code
   * authentication} from {@code SecurityContextHolder}'s thread-local -- populated automatically by
   * Spring Security's filter chain for a normal HTTP request, but NOT for a raw WebSocket
   * message-handling thread. Building the exact same {@link JwtAuthenticationToken} the REST filter
   * chain would (roles claim -> ROLE_-prefixed authorities, matching {@code
   * SecurityConfig#jwtAuthenticationConverter} precisely) and binding it for the duration of this
   * one call exercises the SAME authorization logic REST uses, not a parallel/duplicated check --
   * cleared in a finally block so it never leaks onto this thread past this call.
   */
  private boolean isAuthorizedFor(WebSocketSession session, String brokerAccountId) {
    Jwt jwt = (Jwt) session.getAttributes().get(SESSION_ATTR_JWT);
    if (jwt == null) {
      return false;
    }
    List<String> roles = jwt.getClaimAsStringList("roles");
    List<GrantedAuthority> authorities =
        (roles == null ? List.<String>of() : roles)
            .stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    AbstractAuthenticationToken authentication = new JwtAuthenticationToken(jwt, authorities);
    authentication.setAuthenticated(true);

    SecurityContextHolder.getContext().setAuthentication(authentication);
    try {
      brokerAccountLookupApi.getBrokerAccount(java.util.UUID.fromString(brokerAccountId));
      return true;
    } catch (IllegalArgumentException
        | NoSuchElementException
        | org.springframework.security.access.AccessDeniedException e) {
      return false;
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    subscribersByBrokerAccountId.values().forEach(sessions -> sessions.remove(session));
  }

  /**
   * Called by {@link BrokerConnectionEventConsumer} to fan a real Kafka event out to subscribers.
   */
  public void publish(String brokerAccountId, String jsonPayload) {
    Set<WebSocketSession> sessions = subscribersByBrokerAccountId.get(brokerAccountId);
    if (sessions == null || sessions.isEmpty()) {
      return;
    }
    TextMessage message = new TextMessage(jsonPayload);
    for (WebSocketSession session : sessions) {
      try {
        if (session.isOpen()) {
          session.sendMessage(message);
        }
      } catch (Exception e) {
        log.warn(
            "realtime: failed to push broker-connection update sessionId={}", session.getId(), e);
      }
    }
  }

  private static String accessTokenFrom(URI uri) {
    if (uri == null || uri.getQuery() == null) {
      return null;
    }
    for (String param : uri.getQuery().split("&")) {
      int eq = param.indexOf('=');
      if (eq > 0 && "access_token".equals(param.substring(0, eq))) {
        return param.substring(eq + 1);
      }
    }
    return null;
  }

  private record SubscribeFrame(String action, String channel, String brokerAccountId) {}
}

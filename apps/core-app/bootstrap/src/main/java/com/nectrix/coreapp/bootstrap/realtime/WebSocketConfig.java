package com.nectrix.coreapp.bootstrap.realtime;

import com.nectrix.coreapp.invitations.api.BrokerAccountLookupApi;
import com.nectrix.coreapp.trading.api.CopyRelationshipLookupApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * TICKET-110 — mounts {@link BrokerConnectionWebSocketHandler} at {@code /ws/v1}.
 *
 * <p>{@code copyRelationshipLookupApi} is {@code @Lazy}: {@code CopyRelationshipLookupApiImpl} ->
 * {@code CopyRelationshipService} -> {@code CopyRelationshipUpdatePublisherAdapter} -> {@link
 * BrokerConnectionWebSocketHandler} -> (produced by) this class is a real cycle if resolved
 * eagerly. The lazy proxy defers actually creating that chain until the first {@code
 * copy-relationships} subscribe frame arrives — by then every bean in the cycle already exists.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

  private final JwtDecoder jwtDecoder;
  private final BrokerAccountLookupApi brokerAccountLookupApi;
  private final CopyRelationshipLookupApi copyRelationshipLookupApi;

  public WebSocketConfig(
      JwtDecoder jwtDecoder,
      BrokerAccountLookupApi brokerAccountLookupApi,
      @Lazy CopyRelationshipLookupApi copyRelationshipLookupApi) {
    this.jwtDecoder = jwtDecoder;
    this.brokerAccountLookupApi = brokerAccountLookupApi;
    this.copyRelationshipLookupApi = copyRelationshipLookupApi;
  }

  @Bean
  public BrokerConnectionWebSocketHandler brokerConnectionWebSocketHandler() {
    return new BrokerConnectionWebSocketHandler(
        jwtDecoder, brokerAccountLookupApi, copyRelationshipLookupApi);
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    // setAllowedOriginPatterns("*") -- this is a bearer-token-authenticated channel (the token
    // itself is the auth, checked in afterConnectionEstablished), not cookie-based, so there's no
    // ambient-credential CSRF/XSS-pivot risk the way there would be for a cookie-authenticated
    // WebSocket (see SecurityConfig's own Javadoc on why this whole app is cookie-free).
    registry.addHandler(brokerConnectionWebSocketHandler(), "/ws/v1").setAllowedOriginPatterns("*");
  }
}

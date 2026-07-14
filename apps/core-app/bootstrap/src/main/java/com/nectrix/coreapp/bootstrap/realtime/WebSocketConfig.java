package com.nectrix.coreapp.bootstrap.realtime;

import com.nectrix.coreapp.invitations.api.BrokerAccountLookupApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** TICKET-110 — mounts {@link BrokerConnectionWebSocketHandler} at {@code /ws/v1}. */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

  private final JwtDecoder jwtDecoder;
  private final BrokerAccountLookupApi brokerAccountLookupApi;

  public WebSocketConfig(JwtDecoder jwtDecoder, BrokerAccountLookupApi brokerAccountLookupApi) {
    this.jwtDecoder = jwtDecoder;
    this.brokerAccountLookupApi = brokerAccountLookupApi;
  }

  @Bean
  public BrokerConnectionWebSocketHandler brokerConnectionWebSocketHandler() {
    return new BrokerConnectionWebSocketHandler(jwtDecoder, brokerAccountLookupApi);
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

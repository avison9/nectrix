package com.nectrix.coreapp.auth.config;

import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless bearer-token JSON API — no cookies/sessions, so CSRF protection is inapplicable
 * (docs/17-security-architecture.md). Defining this custom {@link SecurityFilterChain} means Boot's
 * default chain (which would otherwise add {@code formLogin()} and a generated password) never
 * activates — the harmless "Using generated security password: ..." startup log line is inert noise
 * from {@code UserDetailsServiceAutoConfiguration}, not a sign anything is misconfigured.
 *
 * <p>Protected routes ({@code /logout}, both {@code /2fa/*} routes) are named explicitly, and the
 * terminal match is {@code anyRequest().permitAll()} rather than {@code .authenticated()} —
 * deliberately, not an oversight. {@code authorizeHttpRequests} decides allow/deny purely from the
 * request's method+path, before Spring MVC ever resolves a handler; an {@code anyRequest()
 * .authenticated()} catch-all would make Security's {@code AuthenticationEntryPoint} return 401 for
 * an entirely unmapped path like {@code /api/v1/auth/register} (verified by hand — AC1 wants a
 * genuine 404, see {@code UserProvisioningApi}'s Javadoc for why that route must not exist at all).
 * With the catch-all permitAll, unmapped paths fall through to {@code DispatcherServlet}, which
 * 404s them the normal way. Every future protected route (TICKET-006 onward) must be added to this
 * allowlist explicitly — there is no longer a secure-by-default catch-all.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

  /**
   * Validates OUR OWN HS256 access tokens (see JwtService) — entirely separate from {@link
   * com.nectrix.coreapp.auth.service.oauth.OidcIdTokenVerifier}, which verifies Google/Apple's
   * RS256 ID tokens once, synchronously, outside this filter chain.
   */
  @Bean
  public JwtDecoder jwtDecoder(AuthProperties props) {
    byte[] secretBytes = Base64.getDecoder().decode(props.jwt().secret());
    SecretKey key = new SecretKeySpec(secretBytes, "HmacSHA256");
    return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder jwtDecoder)
      throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/oauth/*/callback")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/2fa/enable")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/2fa/verify")
                    .authenticated()
                    // -- add new protected/public auth-adjacent routes here (future tickets:
                    // accept-invite, by-token) — anyRequest() below is intentionally permitAll,
                    // not authenticated(), so genuinely unmapped paths 404 instead of 401; see
                    // class Javadoc.
                    .anyRequest()
                    .permitAll())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2.jwt(
                    jwt ->
                        jwt.decoder(jwtDecoder)
                            .jwtAuthenticationConverter(jwtAuthenticationConverter())));
    return http.build();
  }

  /**
   * Reads the {@code roles} claim (not the default {@code scope}) so TICKET-006's future
   * {@code @PreAuthorize("hasRole(...)")} checks actually match what JwtService issues.
   */
  private JwtAuthenticationConverter jwtAuthenticationConverter() {
    var authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName("roles");
    authorities.setAuthorityPrefix("ROLE_");
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    return converter;
  }
}

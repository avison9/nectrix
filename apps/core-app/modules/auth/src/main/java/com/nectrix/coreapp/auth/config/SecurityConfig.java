package com.nectrix.coreapp.auth.config;

import com.nectrix.coreapp.auth.security.InternalServiceTokenFilter;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
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
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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
@EnableConfigurationProperties({AuthProperties.class, InternalApiProperties.class})
public class SecurityConfig {

  /**
   * The Nectrix-hosted MT5/MT4 terminal-provisioning work's one, narrowly-scoped endpoint that
   * returns a real plaintext broker password — a materially more sensitive capability than anything
   * else under {@code /internal/**}, so it gets its OWN {@code SecurityFilterChain} checking a
   * separate {@link InternalApiProperties#mtTerminalProvisionerToken()}, not the shared {@code
   * serviceToken} every other internal caller uses. {@code @Order(0)} — evaluated BEFORE {@link
   * #internalFilterChain}'s broader {@code /internal/**} matcher below, so this narrower path is
   * claimed by this chain first and never falls through to the shared-token one.
   */
  @Bean
  @Order(0)
  public SecurityFilterChain internalMtTerminalCredentialsFilterChain(
      HttpSecurity http, InternalApiProperties props) throws Exception {
    http.securityMatcher("/internal/broker-accounts/mt-terminal-credentials/**")
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(
            new InternalServiceTokenFilter(props.mtTerminalProvisionerToken()),
            UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
    return http.build();
  }

  /**
   * TICKET-101 — a second, entirely separate filter chain for the rest of {@code /internal/**},
   * evaluated before (lower {@code @Order} = higher priority) the main JWT-based chain below.
   * Requests matching this chain never reach the JWT logic at all — internal-only callers (apps/
   * broker-adapters, apps/mt5-bridge-gateway) have no JWT to present. {@link
   * InternalServiceTokenFilter} does the actual auth check; {@code anyRequest().authenticated()}
   * here just means "the filter above populated a SecurityContext" (a request the filter rejected
   * already got a 401 response and never reaches this authorization check).
   */
  @Bean
  @Order(1)
  public SecurityFilterChain internalFilterChain(HttpSecurity http, InternalApiProperties props)
      throws Exception {
    http.securityMatcher("/internal/**")
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(
            new InternalServiceTokenFilter(props.serviceToken()),
            UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
    return http.build();
  }

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
  @Order(2)
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
                    // TICKET-006 — role/ownership enforcement itself is method-security
                    // (@PreAuthorize/@PostAuthorize on the controller/service), but these still
                    // need to be listed here as .authenticated() so a request with no bearer
                    // token at all gets a clean 401 rather than falling through to
                    // anyRequest().permitAll() below (where an anonymous "authentication" would
                    // only be caught by the method-security check, yielding a less correct 403).
                    .requestMatchers(HttpMethod.GET, "/api/v1/broker-accounts/*")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/impersonate/*")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/ledger-adjustments")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/broker-accounts/*")
                    .authenticated()
                    // TICKET-012 — Admin Portal's account-provisioning form + Audit Log viewer.
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/users")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/audit-log")
                    .authenticated()
                    // TICKET-101 — cTrader OAuth broker-linking flow. The callback is permitAll:
                    // the browser's redirect back from cTrader carries no bearer token, so the
                    // state param itself (validated server-side against Redis) is the proof of
                    // the OAuth round trip — the same reasoning as the existing
                    // /auth/oauth/*/callback permitAll above.
                    .requestMatchers(HttpMethod.GET, "/api/v1/broker/ctrader/authorize-url")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/broker/ctrader/callback")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/broker/ctrader/link")
                    .authenticated()
                    // TICKET-102 — MT5/MT4 direct-credential linking. No callback/permitAll
                    // route needed here: unlike cTrader's OAuth dance, this is a single
                    // authenticated call, start to finish.
                    .requestMatchers(HttpMethod.POST, "/api/v1/broker-accounts/mt5")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/broker-accounts/mt4")
                    .authenticated()
                    // TICKET-103 — symbol-mapping confirmation flow. Ownership/staff-bypass is
                    // method-security (SymbolMappingService's own calls into
                    // BrokerAccountService#getBrokerAccount), same reasoning as the
                    // GET .../broker-accounts/* matcher above.
                    .requestMatchers(HttpMethod.GET, "/api/v1/broker-accounts/*/symbol-mappings")
                    .authenticated()
                    .requestMatchers(HttpMethod.PUT, "/api/v1/broker-accounts/*/symbol-mappings/*")
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

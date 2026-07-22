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
 * an entirely unmapped path (verified by hand against TICKET-005's own AC1, back when {@code
 * /api/v1/auth/register} was itself one such path — see {@code UserProvisioningApi}'s Javadoc for
 * why TICKET-114 later made it a real, deliberate exception). With the catch-all permitAll,
 * unmapped paths fall through to {@code DispatcherServlet}, which 404s them the normal way. Every
 * future protected route (TICKET-006 onward) must be added to this allowlist explicitly — there is
 * no longer a secure-by-default catch-all.
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
                    // TICKET-114 — self-serve "Individual" registration, the one deliberate
                    // exception to the no-self-registration invariant this class's own Javadoc
                    // used to describe as absolute; see RegistrationService/UserProvisioningApi.
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/register")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/2fa/enable")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/2fa/verify")
                    .authenticated()
                    // TICKET-117 bugfix — real 2FA disable (was permanently a dead button).
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/2fa/disable")
                    .authenticated()
                    // TICKET-006 — role/ownership enforcement itself is method-security
                    // (@PreAuthorize/@PostAuthorize on the controller/service), but these still
                    // need to be listed here as .authenticated() so a request with no bearer
                    // token at all gets a clean 401 rather than falling through to
                    // anyRequest().permitAll() below (where an anonymous "authentication" would
                    // only be caught by the method-security check, yielding a less correct 403).
                    .requestMatchers(HttpMethod.GET, "/api/v1/broker-accounts/*")
                    .authenticated()
                    // TICKET-110 — list/PATCH/DELETE/snapshot/positions. list's own query is
                    // scoped to the caller at the SQL layer (BrokerAccountService#listForUser);
                    // PATCH/DELETE use an explicit fetch-then-check-then-mutate guard
                    // (BrokerAccountController calls getBrokerAccount first) since @PostAuthorize
                    // doesn't apply to a same-class self-invocation — see that service's Javadoc.
                    .requestMatchers(HttpMethod.GET, "/api/v1/broker-accounts")
                    .authenticated()
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/broker-accounts/*")
                    .authenticated()
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/broker-accounts/*")
                    .authenticated()
                    // TICKET-101 follow-up — the user's own explicit "stop this account" step,
                    // required before DELETE above (see BrokerAccountService#deleteBrokerAccount's
                    // own Javadoc) — same fetch-then-check-then-mutate guard as PATCH/DELETE.
                    .requestMatchers(HttpMethod.POST, "/api/v1/broker-accounts/*/disconnect")
                    .authenticated()
                    // TICKET-101 follow-up — the on-demand archive-and-delete trigger
                    // (bootstrap.archival.BrokerAccountArchivalController), same
                    // fetch-then-check-then-mutate ownership guard as DELETE/disconnect above.
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/broker-accounts/*/archive-and-delete")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/broker-accounts/*/snapshot")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/broker-accounts/*/positions")
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
                    // TICKET-117 — admin user search/detail/suspend/reinstate. RBAC split
                    // (SUPPORT can view, only ADMIN can suspend/reinstate) is method-security
                    // on AdminController, same reasoning as every other admin matcher above.
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/users")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/users/*")
                    .authenticated()
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/users/*/suspend")
                    .authenticated()
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/users/*/reinstate")
                    .authenticated()
                    // TICKET-117 bugfix — real delete, alongside suspend.
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/admin/users/*")
                    .authenticated()
                    // TICKET-117 — admin dispute raise/list/detail/resolve. RBAC split (resolve
                    // is ADMIN-only, the rest ADMIN+SUPPORT) is method-security on AdminController.
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/fee-ledger")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/fee-ledger/*")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/fee-ledger/*/dispute")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/fee-ledger/*/resolve")
                    .authenticated()
                    // TICKET-122 — tier-change-request self-service (submit + own status) and the
                    // admin list/approve/reject queue. RBAC split (approve/reject is ADMIN+
                    // SUPER_ADMIN-only, listing is ADMIN+SUPPORT+SUPER_ADMIN) is method-security on
                    // TierChangeRequestController/AdminController, same pattern as fee-ledger
                    // above.
                    .requestMatchers(HttpMethod.POST, "/api/v1/account/tier-change-requests")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/account/tier-change-requests/me")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/tier-change-requests")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/tier-change-requests/*")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/admin/tier-change-requests/*/approve")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/tier-change-requests/*/reject")
                    .authenticated()
                    // TICKET-117 — System Health.
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/system-health")
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
                    // TICKET-116 — manual symbol-mapping fallback (creates a row for a canonical
                    // symbol the auto-suggestion probe list never covered).
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/broker-accounts/*/symbol-mappings/*/resolve")
                    .authenticated()
                    // TICKET-111 — Master profile self-service creation (role check is
                    // @PreAuthorize("hasRole('MASTER')") method-security, see
                    // MasterProfileController) + the CopyRelationship state machine (ownership is
                    // method-security, same @perms.isOwnerOrStaff reasoning as broker-accounts
                    // above).
                    .requestMatchers(HttpMethod.POST, "/api/v1/master-profiles")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/master-profiles/*")
                    .authenticated()
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/master-profiles/*")
                    .authenticated()
                    // TICKET-116 — MasterAnalyticsController, ownership enforced by
                    // MasterAnalyticsService's own @PostAuthorize, not this matcher.
                    .requestMatchers(HttpMethod.GET, "/api/v1/master-profiles/*/analytics")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/copy-relationships")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/copy-relationships/*")
                    .authenticated()
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/copy-relationships/*")
                    .authenticated()
                    // TICKET-116 — in-place money-management/risk profile editing.
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/copy-relationships/*/copy-settings")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/copy-relationships/*/trades")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/copy-relationships/*/acknowledge-risk")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/copy-relationships/*/sign-agreement")
                    .authenticated()
                    // TICKET-120 — AC2's presigned-URL retrieval; ownership enforced the same way
                    // every other by-id copy-relationships route is (getCopyRelationship's own
                    // @PostAuthorize).
                    .requestMatchers(HttpMethod.GET, "/api/v1/copy-relationships/*/agreement")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/copy-relationships/*/pause")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/copy-relationships/*/resume")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/copy-relationships/*/stop")
                    .authenticated()
                    // TICKET-112 — public discovery surface (docs/14-api-specification.md §14.4:
                    // "Discovery endpoints remain public/unauthenticated"), see
                    // DiscoveryController's
                    // own Javadoc.
                    .requestMatchers(HttpMethod.GET, "/api/v1/discovery/leaderboard")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/discovery/masters/*")
                    .permitAll()
                    // TICKET-113 — Stripe's own webhook, not a bearer-token caller at all;
                    // security is Stripe's signature scheme, verified in-controller (see
                    // StripeWebhookController's own Javadoc for why this isn't under /internal/**).
                    .requestMatchers(HttpMethod.POST, "/webhooks/stripe")
                    .permitAll()
                    // TICKET-114 — subscription lifecycle. Ownership is enforced inside
                    // SubscriptionService itself (findActiveForUser is scoped to the caller's own
                    // userId), same reasoning as the broker-accounts list/PATCH matchers above.
                    .requestMatchers(HttpMethod.POST, "/api/v1/subscriptions")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/subscriptions/*/cancel")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/subscriptions/me")
                    .authenticated()
                    // TICKET-117 follow-up — self-service settlement/invoice history + dispute
                    // raising for a Master or Follower's own performance_fee_ledger rows.
                    // Ownership is enforced inside FeeLedgerService (same reasoning as the
                    // broker-accounts list/PATCH matchers above), not this matcher.
                    .requestMatchers(HttpMethod.GET, "/api/v1/fee-ledger")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/fee-ledger/*")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/fee-ledger/*/dispute")
                    .authenticated()
                    // TICKET-114 — Individual-mode self-service copy setup. Role-based rejection
                    // (real Master/Follower callers get a 403) is method-layer, same reasoning as
                    // the master-profiles/copy-relationships matchers above.
                    .requestMatchers(HttpMethod.POST, "/api/v1/individual/copy-setup")
                    .authenticated()
                    // TICKET-115 — notification inbox/preferences/push-token registration. All
                    // scoped to the caller's own userId inside the service layer, same reasoning
                    // as every other .authenticated()-only matcher above.
                    .requestMatchers(HttpMethod.GET, "/api/v1/notifications")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/notifications/*/read")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/notification-preferences")
                    .authenticated()
                    .requestMatchers(HttpMethod.PUT, "/api/v1/notification-preferences")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/push-tokens")
                    .authenticated()
                    // TICKET-118 — Master-scoped invitation CRUD (role check is
                    // @PreAuthorize("hasRole('MASTER')") method-security, see
                    // InvitationController), living in modules:invitations, not modules:auth
                    // (see AcceptInviteController's own Javadoc for why) — the route list here
                    // doesn't care which module's controller serves a path.
                    .requestMatchers(HttpMethod.POST, "/api/v1/master/invitations")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/master/invitations")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/master/invitations/*/revoke")
                    .authenticated()
                    // TICKET-118 follow-up — resend isn't a one-shot affair; rotates the token
                    // and re-sends the email, rate-limited per-invitation in InvitationService.
                    .requestMatchers(HttpMethod.POST, "/api/v1/master/invitations/*/resend")
                    .authenticated()
                    // TICKET-119 — Master-scoped Broker IB Link CRUD (role check is
                    // @PreAuthorize("hasRole('MASTER')") method-security, see
                    // BrokerIbLinkController), same "route list doesn't care which module's
                    // controller serves a path" reasoning as the invitations matchers above.
                    .requestMatchers(HttpMethod.POST, "/api/v1/master/broker-ib-links")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/master/broker-ib-links")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/master/broker-ib-links/*/deactivate")
                    .authenticated()
                    // TICKET-120 — Master-scoped BrokerFeeReport generation/review/send/confirm
                    // (role check is @PreAuthorize("hasRole('MASTER')") method-security, see
                    // BrokerFeeReportController), same reasoning as the two matcher groups above.
                    .requestMatchers(HttpMethod.POST, "/api/v1/master/fee-reports")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/master/fee-reports")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/master/fee-reports/*")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/master/fee-reports/*/send")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/master/fee-reports/*/confirm-deducted")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/master/fee-reports/*/confirm-paid")
                    .authenticated()
                    // TICKET-118 — public, token-gated (rate-limited in-controller, not here —
                    // see PublicInvitationController/AcceptInviteController's own
                    // InvitationRateLimiterService).
                    .requestMatchers(HttpMethod.GET, "/api/v1/invitations/by-token/*")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/accept-invite")
                    .permitAll()
                    // TICKET-118 — the invitation-acceptance flow's own copy-relationship
                    // creation step (modules:trading's InvitationCopySetupController).
                    .requestMatchers(HttpMethod.GET, "/api/v1/users/me/pending-invitation")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/copy-relationships/from-invitation")
                    .authenticated()
                    // TICKET-118 follow-up — Follower refers a prospect (nominate), Master reviews
                    // in their inbox (list/mark-invited/dismiss). Role split
                    // (FOLLOWER creates, MASTER reviews) is method-security on
                    // ProspectNominationController, same reasoning as every other role-gated
                    // matcher above.
                    .requestMatchers(HttpMethod.POST, "/api/v1/prospect-nominations")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/prospect-nominations/mine")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/master/prospect-nominations")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/master/prospect-nominations/*/mark-invited")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/master/prospect-nominations/*/dismiss")
                    .authenticated()
                    // -- add new protected/public auth-adjacent routes here — anyRequest() below
                    // is intentionally permitAll, not authenticated(), so genuinely unmapped
                    // paths 404 instead of 401; see class Javadoc.
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

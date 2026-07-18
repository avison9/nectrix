package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.invitations.client.BrokerAdaptersInternalClient;
import com.nectrix.coreapp.invitations.domain.BrokerAccount;
import com.nectrix.coreapp.invitations.service.BrokerLinkingService;
import com.nectrix.coreapp.invitations.service.TwoFactorRequiredException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * TICKET-101 — the real cTrader OAuth linking flow, docs/07-auth-onboarding-broker-linking.md §7.6.
 * {@code authorize-url}/{@code link} require an authenticated principal (SecurityConfig); {@code
 * callback} is deliberately {@code permitAll()} — the browser's redirect back from cTrader carries
 * no bearer token, so the {@code state} param itself (validated server-side against Redis) is the
 * proof of the OAuth round trip, the same reasoning SecurityConfig's Javadoc gives for the existing
 * Google/Apple oauth callback route.
 *
 * <p><b>Real, live-verified integration requirement (found during this ticket's own live-
 * verification pass against a real cTrader demo account):</b> unlike a spec-compliant OAuth2
 * provider, cTrader's authorize redirect does NOT echo the {@code state} query param back —
 * cTrader's own redirect only carries {@code ?code=...}. This app's own {@code state}-based CSRF
 * design is still correct and is NOT a bug to fix here (this codebase is deliberately cookie-free —
 * see SecurityConfig's own Javadoc — so a cookie-based fallback would be a first, inconsistent
 * exception); the responsibility shifts to whoever builds the frontend piece: it MUST parse {@code
 * state} out of the {@code authorize_url} returned by this endpoint BEFORE navigating the browser
 * there (e.g. {@code new URL(authorizeUrl).searchParams.get("state")}), persist it (e.g.
 * localStorage — it must survive a full top-level navigation, so in-memory JS state alone won't
 * do), and re-attach it as {@code state} in the {@link #callback} request body once the browser
 * lands back with only {@code code} in the URL. See this ticket's live-verification runbook for the
 * exact manual reproduction.
 */
@RestController
public class BrokerAccountOAuthController {

  private final BrokerLinkingService linkingService;

  public BrokerAccountOAuthController(BrokerLinkingService linkingService) {
    this.linkingService = linkingService;
  }

  @GetMapping("/api/v1/broker/ctrader/authorize-url")
  public AuthorizeUrlResponse authorizeUrl(@AuthenticationPrincipal Jwt jwt) {
    UUID userId = currentUserId(jwt);
    return new AuthorizeUrlResponse(linkingService.beginAuthorization(userId));
  }

  @PostMapping("/api/v1/broker/ctrader/callback")
  public CallbackResponse callback(@RequestBody CallbackRequest request) {
    BrokerLinkingService.CallbackResult result =
        linkingService.handleCallback(request.code(), request.state());
    return new CallbackResponse(
        result.linkSessionId(), result.accounts().stream().map(CallbackAccount::from).toList());
  }

  @PostMapping("/api/v1/broker/ctrader/link")
  public BrokerAccount link(@AuthenticationPrincipal Jwt jwt, @RequestBody LinkRequest request) {
    requireTwoFactor(jwt);
    UUID userId = currentUserId(jwt);
    return linkingService.linkAccount(
        userId,
        callerRoles(jwt),
        request.linkSessionId(),
        request.ctidTraderAccountId(),
        request.isLive(),
        request.displayLabel(),
        request.connectionRole(),
        request.openedViaIbLinkId());
  }

  private UUID currentUserId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  /**
   * TICKET-114 — master/follower-slot enforcement needs to know whether the caller is a real
   * Master/Follower (unaffected) or Individual mode (subject to plan limits).
   */
  private List<String> callerRoles(Jwt jwt) {
    List<String> roles = jwt.getClaimAsStringList("roles");
    return roles != null ? roles : List.of();
  }

  /**
   * AC1 — mandatory 2FA gate, applied unconditionally (see TwoFactorRequiredException's Javadoc).
   */
  private void requireTwoFactor(Jwt jwt) {
    Boolean twoFactorEnabled = jwt.getClaimAsBoolean("two_factor_enabled");
    if (twoFactorEnabled == null || !twoFactorEnabled) {
      throw new TwoFactorRequiredException();
    }
  }

  public record AuthorizeUrlResponse(String authorizeUrl) {}

  public record CallbackRequest(String code, String state) {}

  public record CallbackResponse(String linkSessionId, List<CallbackAccount> accounts) {}

  public record CallbackAccount(
      long ctidTraderAccountId, boolean isLive, long traderLogin, String brokerTitleShort) {
    static CallbackAccount from(BrokerAdaptersInternalClient.CtraderAccount account) {
      return new CallbackAccount(
          account.ctidTraderAccountId(),
          account.isLive(),
          account.traderLogin(),
          account.brokerTitleShort());
    }
  }

  public record LinkRequest(
      String linkSessionId,
      long ctidTraderAccountId,
      boolean isLive,
      String displayLabel,
      String connectionRole,
      UUID openedViaIbLinkId) {}
}

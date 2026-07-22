package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.invitations.service.MtLinkingService;
import com.nectrix.coreapp.invitations.service.TwoFactorRequiredException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * TICKET-102 — MT5/MT4's direct-credential linking endpoints, docs/14-api-specification.md §14.3.
 * Unlike {@link BrokerAccountOAuthController}'s three-step OAuth dance, this is a single
 * authenticated call: submit terminal credentials, get back a pairing token + gateway URL to paste
 * into the EA. {@link com.nectrix.coreapp.invitations.service.BrokerAccountAlreadyLinkedException}
 * thrown by {@link MtLinkingService} is handled by {@link BrokerAccountOAuthExceptionHandler} — see
 * that class's Javadoc for why the same package's advice classes apply here automatically.
 */
@RestController
public class BrokerAccountMtController {

  private final MtLinkingService linkingService;

  public BrokerAccountMtController(MtLinkingService linkingService) {
    this.linkingService = linkingService;
  }

  @PostMapping("/api/v1/broker-accounts/mt5")
  public LinkResponse linkMt5(
      @AuthenticationPrincipal Jwt jwt, @RequestBody LinkRequestBody request) {
    requireTwoFactor(jwt);
    return LinkResponse.from(
        linkingService.linkMt5(currentUserId(jwt), callerRoles(jwt), toServiceRequest(request)));
  }

  @PostMapping("/api/v1/broker-accounts/mt4")
  public LinkResponse linkMt4(
      @AuthenticationPrincipal Jwt jwt, @RequestBody LinkRequestBody request) {
    requireTwoFactor(jwt);
    return LinkResponse.from(
        linkingService.linkMt4(currentUserId(jwt), callerRoles(jwt), toServiceRequest(request)));
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

  private MtLinkingService.LinkRequest toServiceRequest(LinkRequestBody body) {
    return new MtLinkingService.LinkRequest(
        body.login(),
        body.password(),
        body.server(),
        body.isDemo(),
        body.displayLabel(),
        body.connectionRole(),
        body.openedViaIbLinkId(),
        body.brokerName());
  }

  public record LinkRequestBody(
      String login,
      String password,
      String server,
      boolean isDemo,
      String displayLabel,
      String connectionRole,
      UUID openedViaIbLinkId,
      String brokerName) {}

  public record LinkResponse(
      String id, String pairingToken, String gatewayUrl, String connectionStatus) {
    static LinkResponse from(MtLinkingService.LinkResult result) {
      return new LinkResponse(
          result.brokerAccountId().toString(),
          result.pairingToken(),
          result.gatewayUrl(),
          "PENDING");
    }
  }
}

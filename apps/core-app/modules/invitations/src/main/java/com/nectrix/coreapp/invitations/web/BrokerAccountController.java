package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.invitations.client.BrokerAdaptersInternalClient;
import com.nectrix.coreapp.invitations.domain.BrokerAccount;
import com.nectrix.coreapp.invitations.service.BrokerAccountService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Any authenticated user may call the by-id/PATCH/DELETE/snapshot/positions routes — ownership vs.
 * staff-bypass is enforced by {@link BrokerAccountService#getBrokerAccount}'s
 * {@code @PostAuthorize}, not a route-level role check. {@code list} is the one query this class
 * scopes itself (at the SQL layer, via {@link BrokerAccountService#listForUser}) since
 * {@code @PostAuthorize} only ever guards a single already-fetched object, never a collection —
 * docs/17-security-architecture.md §17.3's own "scope every query" requirement applied to a list
 * endpoint specifically.
 */
@RestController
public class BrokerAccountController {

  private final BrokerAccountService service;
  private final BrokerAdaptersInternalClient brokerAdaptersClient;

  public BrokerAccountController(
      BrokerAccountService service, BrokerAdaptersInternalClient brokerAdaptersClient) {
    this.service = service;
    this.brokerAdaptersClient = brokerAdaptersClient;
  }

  @GetMapping("/api/v1/broker-accounts")
  public List<BrokerAccount> list(@AuthenticationPrincipal Jwt jwt) {
    return service.listForUser(currentUserId(jwt));
  }

  @GetMapping("/api/v1/broker-accounts/{id}")
  public BrokerAccount getById(@PathVariable UUID id) {
    return service.getBrokerAccount(id);
  }

  @PatchMapping("/api/v1/broker-accounts/{id}")
  public BrokerAccount patch(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody PatchRequest request) {
    // Explicit fetch-then-check-then-mutate — see BrokerAccountService's own Javadoc for why the
    // ownership check can't live inside the mutating method itself.
    BrokerAccount existing = service.getBrokerAccount(id);
    return service.updateBrokerAccount(
        existing, request.displayLabel(), request.connectionRole(), callerRoles(jwt));
  }

  /**
   * TICKET-101 follow-up — the user's own deliberate "stop this account" step, required before
   * {@link #delete} (see {@code BrokerAccountService#deleteBrokerAccount}'s own Javadoc).
   */
  @PostMapping("/api/v1/broker-accounts/{id}/disconnect")
  public BrokerAccount disconnect(@PathVariable UUID id) {
    BrokerAccount existing = service.getBrokerAccount(id);
    return service.disconnectBrokerAccount(existing);
  }

  /**
   * Bugfix — the reverse of {@link #disconnect}: a disconnected account previously had no
   * self-service way back short of deleting and fully re-linking from scratch via OAuth.
   */
  @PostMapping("/api/v1/broker-accounts/{id}/reconnect")
  public BrokerAccount reconnect(@PathVariable UUID id) {
    BrokerAccount existing = service.getBrokerAccount(id);
    return service.reconnectBrokerAccount(existing);
  }

  @DeleteMapping("/api/v1/broker-accounts/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    BrokerAccount existing = service.getBrokerAccount(id);
    service.deleteBrokerAccount(existing);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/api/v1/broker-accounts/{id}/snapshot")
  public BrokerAdaptersInternalClient.AccountSnapshot snapshot(@PathVariable UUID id) {
    BrokerAccount account = service.getBrokerAccount(id);
    return brokerAdaptersClient.getAccountSnapshot(account.brokerType(), id.toString());
  }

  @GetMapping("/api/v1/broker-accounts/{id}/positions")
  public List<BrokerAdaptersInternalClient.NormalizedPosition> positions(@PathVariable UUID id) {
    BrokerAccount account = service.getBrokerAccount(id);
    return brokerAdaptersClient.getOpenPositions(account.brokerType(), id.toString());
  }

  private UUID currentUserId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  private List<String> callerRoles(Jwt jwt) {
    List<String> roles = jwt.getClaimAsStringList("roles");
    return roles != null ? roles : List.of();
  }

  public record PatchRequest(String displayLabel, String connectionRole) {}
}

package com.nectrix.coreapp.invitations.web;

import com.nectrix.coreapp.invitations.repository.BrokerAccountRepository;
import com.nectrix.coreapp.invitations.service.BrokerAccountInternalService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * TICKET-101 — the internal-only HTTP surface apps/broker-adapters (Go) calls. Reachable only under
 * {@code /internal/**}, guarded by SecurityConfig's second, shared-secret-header filter chain
 * (never the normal JWT path) and a K8s NetworkPolicy (deploy/base/core-app) restricting it to
 * in-cluster callers.
 *
 * <p>Every response record here is explicitly {@code @JsonNaming(LowerCamelCaseStrategy)},
 * overriding the app-wide snake_case Jackson convention (application.yml's {@code
 * spring.jackson.property-naming-strategy: SNAKE_CASE}, meant for the public {@code /api/v1/**}
 * contract) — Go's {@code coreappclient} package expects camelCase JSON, matching cTrader's own
 * field naming convention these values ultimately come from (e.g. {@code ctidTraderAccountId}).
 */
@RestController
public class BrokerAccountInternalController {

  private final BrokerAccountInternalService service;

  public BrokerAccountInternalController(BrokerAccountInternalService service) {
    this.service = service;
  }

  @GetMapping("/internal/broker-accounts")
  public List<AccountRefResponse> listAccounts(
      @RequestParam String status, @RequestParam String brokerType) {
    List<String> statuses = List.of(status.split(","));
    return service.listAccounts(statuses, brokerType).stream()
        .map(AccountRefResponse::from)
        .toList();
  }

  @GetMapping("/internal/broker-accounts/credentials/{id}")
  public CredentialsResponse credentials(@PathVariable UUID id) {
    return CredentialsResponse.from(service.fetchCredentials(id));
  }

  @PostMapping("/internal/broker-accounts/{id}/connection-status")
  public void updateConnectionStatus(
      @PathVariable UUID id, @RequestBody ConnectionStatusRequest request) {
    service.updateConnectionStatus(id, request.status(), request.detail());
  }

  @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
  public record AccountRefResponse(String id, String status) {
    static AccountRefResponse from(BrokerAccountRepository.AccountRef ref) {
      return new AccountRefResponse(ref.id().toString(), ref.connectionStatus());
    }
  }

  @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
  public record CredentialsResponse(
      String accessToken, String refreshToken, long ctidTraderAccountId, boolean isLive) {
    static CredentialsResponse from(BrokerAccountInternalService.DecryptedCredentials creds) {
      return new CredentialsResponse(
          creds.accessToken(), creds.refreshToken(), creds.ctidTraderAccountId(), creds.isLive());
    }
  }

  @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
  public record ConnectionStatusRequest(String status, String detail) {}
}

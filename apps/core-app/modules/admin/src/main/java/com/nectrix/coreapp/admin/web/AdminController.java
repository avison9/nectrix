package com.nectrix.coreapp.admin.web;

import com.nectrix.coreapp.admin.repository.AuditLogRepository;
import com.nectrix.coreapp.auth.api.ImpersonationApi;
import com.nectrix.coreapp.auth.api.ImpersonationResult;
import com.nectrix.coreapp.invitations.api.BrokerAccountLookupApi;
import com.nectrix.coreapp.invitations.api.BrokerAccountView;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * TICKET-006's role-gated (coarse, gateway-level) demo endpoints, docs/17-security-architecture.md
 * §17.3. Every route here uses plain {@code @PreAuthorize(hasRole(...)/hasAnyRole(...))} — already
 * fully wired by TICKET-005's {@code JwtAuthenticationConverter}, no custom annotation needed.
 *
 * <p>{@code /ledger-adjustments} is deliberately ADMIN-only (not SUPPORT) — this is the endpoint
 * that demonstrates docs/12-analytics-notifications-admin.md §12.3's "SUPPORT... cannot adjust
 * financial ledgers" distinction. It writes one audit_log row and returns 204 — no real
 * `performance_fee_ledger` interaction (that logic is Phase 1, docs/11-fee-engine-billing.md).
 */
@RestController
public class AdminController {

  private final ImpersonationApi impersonationApi;
  private final BrokerAccountLookupApi brokerAccountLookupApi;
  private final AuditLogRepository auditLogRepository;
  private final ObjectMapper objectMapper;

  public AdminController(
      ImpersonationApi impersonationApi,
      BrokerAccountLookupApi brokerAccountLookupApi,
      AuditLogRepository auditLogRepository,
      ObjectMapper objectMapper) {
    this.impersonationApi = impersonationApi;
    this.brokerAccountLookupApi = brokerAccountLookupApi;
    this.auditLogRepository = auditLogRepository;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/api/v1/admin/impersonate/{userId}")
  @PreAuthorize("hasAnyRole('ADMIN','SUPPORT')")
  public ImpersonationResult impersonate(
      @PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
    UUID actingAdminId = currentUserId(jwt);
    ImpersonationResult result = impersonationApi.impersonate(userId, actingAdminId);
    auditLogRepository.insert(
        actingAdminId,
        "ADMIN",
        "IMPERSONATE_START",
        "USER",
        userId.toString(),
        objectMapper.writeValueAsString(Map.of("impersonated_user_id", userId.toString())));
    return result;
  }

  @PostMapping("/api/v1/admin/ledger-adjustments")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Void> adjustLedger(
      @RequestBody LedgerAdjustmentRequest request, @AuthenticationPrincipal Jwt jwt) {
    UUID actingAdminId = currentUserId(jwt);
    auditLogRepository.insert(
        actingAdminId,
        "ADMIN",
        "LEDGER_ADJUSTMENT",
        request.targetType(),
        request.targetId(),
        objectMapper.writeValueAsString(
            Map.of("amount", request.amount().toPlainString(), "reason", request.reason())));
    return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
  }

  @GetMapping("/api/v1/admin/broker-accounts/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','SUPPORT')")
  public BrokerAccountView getBrokerAccount(@PathVariable UUID id) {
    return brokerAccountLookupApi.getBrokerAccount(id);
  }

  private UUID currentUserId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  public record LedgerAdjustmentRequest(
      String targetType, String targetId, BigDecimal amount, String reason) {}
}

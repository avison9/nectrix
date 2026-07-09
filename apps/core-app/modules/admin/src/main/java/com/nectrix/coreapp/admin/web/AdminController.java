package com.nectrix.coreapp.admin.web;

import com.nectrix.coreapp.admin.repository.AuditLogRepository;
import com.nectrix.coreapp.auth.api.ImpersonationApi;
import com.nectrix.coreapp.auth.api.ImpersonationResult;
import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.invitations.api.BrokerAccountLookupApi;
import com.nectrix.coreapp.invitations.api.BrokerAccountView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.web.bind.annotation.RequestParam;
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

  /**
   * TICKET-012 — the only roles {@code POST /api/v1/admin/users} may grant. MASTER is deliberately
   * excluded: {@code POST /api/v1/admin/masters} (which also creates a {@code master_profiles} row)
   * is a separate, deferred Phase 1 endpoint — see the ticket's own scope note.
   */
  private static final Set<String> PROVISIONABLE_ROLES = Set.of("ADMIN", "SUPPORT");

  private final ImpersonationApi impersonationApi;
  private final BrokerAccountLookupApi brokerAccountLookupApi;
  private final UserProvisioningApi userProvisioningApi;
  private final AuditLogRepository auditLogRepository;
  private final ObjectMapper objectMapper;

  public AdminController(
      ImpersonationApi impersonationApi,
      BrokerAccountLookupApi brokerAccountLookupApi,
      UserProvisioningApi userProvisioningApi,
      AuditLogRepository auditLogRepository,
      ObjectMapper objectMapper) {
    this.impersonationApi = impersonationApi;
    this.brokerAccountLookupApi = brokerAccountLookupApi;
    this.userProvisioningApi = userProvisioningApi;
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

  /**
   * TICKET-012 — the platform's account-creation entry point (there is no self-registration
   * anywhere, docs/05-domain-model.md §5.0). ADMIN-only (not SUPPORT — same "SUPPORT cannot action
   * platform config" distinction {@code /ledger-adjustments} demonstrates,
   * docs/12-analytics-notifications-admin.md §12.3).
   */
  @PostMapping("/api/v1/admin/users")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<ProvisionUserResponse> provisionUser(
      @RequestBody ProvisionUserRequest request, @AuthenticationPrincipal Jwt jwt) {
    if (!PROVISIONABLE_ROLES.contains(request.role())) {
      throw new IllegalArgumentException("role must be one of " + PROVISIONABLE_ROLES);
    }
    UUID actingAdminId = currentUserId(jwt);
    UUID newUserId =
        userProvisioningApi.createUser(
            request.email(),
            request.password(),
            request.displayName(),
            actingAdminId,
            null,
            null,
            null);
    userProvisioningApi.grantRole(newUserId, request.role());
    auditLogRepository.insert(
        actingAdminId,
        "ADMIN",
        "USER_PROVISIONED",
        "USER",
        newUserId.toString(),
        objectMapper.writeValueAsString(Map.of("role", request.role())));
    return ResponseEntity.status(HttpStatus.CREATED).body(new ProvisionUserResponse(newUserId));
  }

  /**
   * TICKET-012 — backs the Audit Log viewer (`docs/12-analytics-notifications-admin.md` §12.3's
   * "Audit Log" capability area). SUPPORT can read (view/assist is within their scope) but not
   * provision accounts — same split as impersonation vs. ledger-adjustment above.
   */
  @GetMapping("/api/v1/admin/audit-log")
  @PreAuthorize("hasAnyRole('ADMIN','SUPPORT')")
  public AuditLogPageResponse listAuditLog(
      @RequestParam(required = false) UUID actorUserId,
      @RequestParam(required = false) String targetType,
      @RequestParam(required = false) String targetId,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int pageSize) {
    AuditLogRepository.Filter filter =
        new AuditLogRepository.Filter(actorUserId, targetType, targetId, from, to);
    List<AuditLogRepository.AuditLogEntry> entries =
        auditLogRepository.findPage(filter, page, pageSize);
    long total = auditLogRepository.count(filter);
    return new AuditLogPageResponse(entries, total, page, pageSize);
  }

  private UUID currentUserId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  public record LedgerAdjustmentRequest(
      String targetType, String targetId, BigDecimal amount, String reason) {}

  public record ProvisionUserRequest(
      String email, String password, String displayName, String role) {}

  public record ProvisionUserResponse(UUID id) {}

  public record AuditLogPageResponse(
      List<AuditLogRepository.AuditLogEntry> entries, long total, int page, int pageSize) {}
}

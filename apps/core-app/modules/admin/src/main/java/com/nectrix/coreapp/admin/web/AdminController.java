package com.nectrix.coreapp.admin.web;

import com.nectrix.coreapp.admin.repository.AdminRepository;
import com.nectrix.coreapp.admin.repository.AdminRepository.BrokerConnectionCount;
import com.nectrix.coreapp.admin.service.KafkaConsumerLagService;
import com.nectrix.coreapp.admin.service.KafkaConsumerLagService.ConsumerGroupLag;
import com.nectrix.coreapp.audit.repository.AuditLogRepository;
import com.nectrix.coreapp.auth.api.ImpersonationApi;
import com.nectrix.coreapp.auth.api.ImpersonationResult;
import com.nectrix.coreapp.auth.api.UserAdminApi;
import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.auth.api.UserView;
import com.nectrix.coreapp.billing.api.FeeLedgerAdminApi;
import com.nectrix.coreapp.billing.api.FeeLedgerAdminApi.FeeLedgerDetailView;
import com.nectrix.coreapp.billing.api.FeeLedgerAdminApi.FeeLedgerSummaryView;
import com.nectrix.coreapp.billing.api.FeeLedgerAdminApi.UnderlyingTradeView;
import com.nectrix.coreapp.invitations.api.BrokerAccountLookupApi;
import com.nectrix.coreapp.invitations.api.BrokerAccountView;
import java.math.BigDecimal;
import java.time.Duration;
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
import org.springframework.web.bind.annotation.PatchMapping;
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
  private final UserAdminApi userAdminApi;
  private final FeeLedgerAdminApi feeLedgerAdminApi;
  private final AdminRepository adminRepository;
  private final KafkaConsumerLagService kafkaConsumerLagService;
  private final AuditLogRepository auditLogRepository;
  private final ObjectMapper objectMapper;

  /** System Health's Copy Engine throughput/error-rate window — see {@link #getSystemHealth}. */
  private static final Duration COPY_ENGINE_WINDOW = Duration.ofMinutes(15);

  public AdminController(
      ImpersonationApi impersonationApi,
      BrokerAccountLookupApi brokerAccountLookupApi,
      UserProvisioningApi userProvisioningApi,
      UserAdminApi userAdminApi,
      FeeLedgerAdminApi feeLedgerAdminApi,
      AdminRepository adminRepository,
      KafkaConsumerLagService kafkaConsumerLagService,
      AuditLogRepository auditLogRepository,
      ObjectMapper objectMapper) {
    this.impersonationApi = impersonationApi;
    this.brokerAccountLookupApi = brokerAccountLookupApi;
    this.userProvisioningApi = userProvisioningApi;
    this.userAdminApi = userAdminApi;
    this.feeLedgerAdminApi = feeLedgerAdminApi;
    this.adminRepository = adminRepository;
    this.kafkaConsumerLagService = kafkaConsumerLagService;
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

  /**
   * TICKET-117 — admin user search. A blank/absent {@code search} returns every user (newest
   * first), see {@code UserRepository#search}'s own Javadoc.
   */
  @GetMapping("/api/v1/admin/users")
  @PreAuthorize("hasAnyRole('ADMIN','SUPPORT')")
  public List<UserView> searchUsers(
      @RequestParam(required = false, defaultValue = "") String search,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int pageSize) {
    return userAdminApi.search(search, page, pageSize);
  }

  /**
   * TICKET-117 — admin user detail, including linked broker accounts (via {@link
   * BrokerAccountLookupApi#listForUser}) with each account's real {@code connectionStatus}/{@code
   * lastHealthCheckAt} — the whole reason this endpoint exists rather than just reusing {@link
   * #searchUsers}'s summary rows.
   */
  @GetMapping("/api/v1/admin/users/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','SUPPORT')")
  public UserDetailResponse getUser(@PathVariable UUID id) {
    UserView user = userAdminApi.getUser(id);
    List<BrokerAccountView> brokerAccounts = brokerAccountLookupApi.listForUser(id);
    return new UserDetailResponse(user, brokerAccounts);
  }

  /**
   * ADMIN-only (not SUPPORT) — same "SUPPORT cannot action platform state" distinction {@code
   * /ledger-adjustments} demonstrates. Suspension is enforced for real at {@code
   * AuthService#issueNewSession} — both a fresh login AND an existing refresh token stop working
   * immediately, not just future logins.
   */
  @PatchMapping("/api/v1/admin/users/{id}/suspend")
  @PreAuthorize("hasRole('ADMIN')")
  public UserView suspendUser(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    UUID actingAdminId = currentUserId(jwt);
    userAdminApi.updateStatus(id, "SUSPENDED");
    auditLogRepository.insert(
        actingAdminId, "ADMIN", "USER_SUSPENDED", "USER", id.toString(), null);
    return userAdminApi.getUser(id);
  }

  @PatchMapping("/api/v1/admin/users/{id}/reinstate")
  @PreAuthorize("hasRole('ADMIN')")
  public UserView reinstateUser(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    UUID actingAdminId = currentUserId(jwt);
    userAdminApi.updateStatus(id, "ACTIVE");
    auditLogRepository.insert(
        actingAdminId, "ADMIN", "USER_REINSTATED", "USER", id.toString(), null);
    return userAdminApi.getUser(id);
  }

  /**
   * TICKET-117 — the Disputes list view. {@code status} defaults to {@code DISPUTED} — see the
   * ticket's own scope note.
   */
  @GetMapping("/api/v1/admin/fee-ledger")
  @PreAuthorize("hasAnyRole('ADMIN','SUPPORT')")
  public List<FeeLedgerSummaryView> listFeeLedger(
      @RequestParam(defaultValue = "DISPUTED") String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int pageSize) {
    return feeLedgerAdminApi.listByStatus(status, page, pageSize);
  }

  @GetMapping("/api/v1/admin/fee-ledger/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','SUPPORT')")
  public FeeLedgerDetailResponse getFeeLedgerDetail(@PathVariable UUID id) {
    FeeLedgerDetailView detail = feeLedgerAdminApi.getDetail(id);
    List<UnderlyingTradeView> trades = feeLedgerAdminApi.listUnderlyingTrades(id);
    return new FeeLedgerDetailResponse(detail, trades);
  }

  /**
   * TICKET-117 — the only real way {@code performance_fee_ledger.status} can ever become {@code
   * DISPUTED}. ADMIN+SUPPORT (view/assist is within SUPPORT's scope, same as impersonation above) —
   * only {@link #resolveFeeLedgerDispute} is ADMIN-only.
   */
  @PostMapping("/api/v1/admin/fee-ledger/{id}/dispute")
  @PreAuthorize("hasAnyRole('ADMIN','SUPPORT')")
  public ResponseEntity<Void> raiseDispute(
      @PathVariable UUID id,
      @RequestBody DisputeRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    UUID actingUserId = currentUserId(jwt);
    feeLedgerAdminApi.dispute(id);
    auditLogRepository.insert(
        actingUserId,
        "ADMIN",
        "FEE_LEDGER_DISPUTED",
        "FEE_LEDGER",
        id.toString(),
        objectMapper.writeValueAsString(Map.of("reason", request.reason())));
    return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
  }

  /**
   * ADMIN-only — matches the ticket's own RBAC line (dispute resolution is a financial-ledger
   * action, same "SUPPORT cannot adjust financial ledgers" distinction {@code /ledger-adjustments}
   * demonstrates). See {@link FeeLedgerAdminApi#resolve}'s own Javadoc for the status-transition
   * rules.
   */
  @PostMapping("/api/v1/admin/fee-ledger/{id}/resolve")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Void> resolveFeeLedgerDispute(
      @PathVariable UUID id,
      @RequestBody ResolveRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    UUID actingAdminId = currentUserId(jwt);
    feeLedgerAdminApi.resolve(
        id, request.resolution(), request.note(), request.adjustedAmount(), actingAdminId);
    auditLogRepository.insert(
        actingAdminId,
        "ADMIN",
        "FEE_LEDGER_RESOLVED",
        "FEE_LEDGER",
        id.toString(),
        objectMapper.writeValueAsString(Map.of("resolution", request.resolution())));
    return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
  }

  /**
   * TICKET-117 — System Health. Built from Postgres + a real Kafka consumer/AdminClient, not
   * Prometheus queries — the {@code nectrix-dev} VM's own docker-compose.yml explicitly excludes
   * the observability stack, so there's no Prometheus anywhere outside local dev/CI to query
   * against the one persistent environment that exists (see this ticket's own plan Context). Every
   * number here is real: a genuinely-empty table/topic reports a real zero, never a placeholder.
   */
  @GetMapping("/api/v1/admin/system-health")
  @PreAuthorize("hasAnyRole('ADMIN','SUPPORT')")
  public SystemHealthResponse getSystemHealth() {
    Instant windowStart = Instant.now().minus(COPY_ENGINE_WINDOW);
    List<BrokerConnectionCount> brokerConnections =
        adminRepository.countBrokerConnectionsByTypeAndStatus();
    long tradesInWindow = adminRepository.countCopiedTradesSince(windowStart);
    long failedInWindow = adminRepository.countFailedCopiedTradesSince(windowStart);
    long driftLastHour =
        adminRepository.countReconciliationDriftSince(Instant.now().minus(Duration.ofHours(1)));
    List<ConsumerGroupLag> consumerLag = kafkaConsumerLagService.currentLag();
    CopyEngineHealth copyEngine =
        new CopyEngineHealth((int) COPY_ENGINE_WINDOW.toMinutes(), tradesInWindow, failedInWindow);
    return new SystemHealthResponse(brokerConnections, copyEngine, driftLastHour, consumerLag);
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

  public record UserDetailResponse(UserView user, List<BrokerAccountView> brokerAccounts) {}

  public record FeeLedgerDetailResponse(
      FeeLedgerDetailView ledger, List<UnderlyingTradeView> trades) {}

  public record DisputeRequest(String reason) {}

  public record ResolveRequest(String resolution, String note, BigDecimal adjustedAmount) {}

  public record CopyEngineHealth(int windowMinutes, long tradesInWindow, long failedInWindow) {}

  public record SystemHealthResponse(
      List<BrokerConnectionCount> brokerConnections,
      CopyEngineHealth copyEngine,
      long reconciliationDriftLastHour,
      List<ConsumerGroupLag> kafkaConsumerLag) {}
}

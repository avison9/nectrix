package com.nectrix.coreapp.admin.web;

import com.nectrix.coreapp.admin.client.MtTerminalHostClient;
import com.nectrix.coreapp.admin.client.MtTerminalHostClient.TerminalStatus;
import com.nectrix.coreapp.admin.repository.AdminRepository;
import com.nectrix.coreapp.admin.repository.AdminRepository.BrokerConnectionCount;
import com.nectrix.coreapp.admin.repository.AdminRepository.MtBrokerAccountRef;
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
import com.nectrix.coreapp.invitations.api.TierChangeRequestAdminApi;
import com.nectrix.coreapp.invitations.api.TierChangeRequestAdminApi.TierChangeRequestView;
import com.nectrix.coreapp.trading.api.AdminCopyRelationshipApi;
import com.nectrix.coreapp.trading.api.AdminCopyRelationshipApi.LinkedCopyRelationshipView;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
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
   * TICKET-012 — the only roles {@code POST /api/v1/admin/users} may grant. TICKET-012's own text
   * originally deferred MASTER to a separate {@code POST /api/v1/admin/masters} endpoint (since
   * {@code master_profiles} didn't exist yet, and creating one requires a {@code
   * primary_broker_account_id} — a real, encrypted-credential-backed row an admin can't fabricate
   * on a user's behalf). Now that TICKET-111 has shipped a real self-service {@code
   * MasterProfileController} (`hasRole('MASTER')`, the caller creates their own profile against
   * their own already-linked broker account), that gap is closed differently than originally
   * planned: provisioning here only ever grants the bare role, exactly like ADMIN/SUPPORT — the
   * newly-Master/Follower user completes their own profile/onboarding afterward through the
   * already-real self-service flows (master-profile creation, broker linking, accept-invite), same
   * as any other account reaching that role. {@code SUPER_ADMIN} and {@code PARTNER} are
   * deliberately still excluded — SUPER_ADMIN is bootstrap/migration-only (granting it through a
   * self-service dropdown would let any ADMIN mint a peer/superior), and PARTNER has no
   * provisioning ticket of its own yet.
   */
  private static final Set<String> PROVISIONABLE_ROLES =
      Set.of("ADMIN", "SUPPORT", "MASTER", "FOLLOWER");

  private final ImpersonationApi impersonationApi;
  private final BrokerAccountLookupApi brokerAccountLookupApi;
  private final UserProvisioningApi userProvisioningApi;
  private final UserAdminApi userAdminApi;
  private final FeeLedgerAdminApi feeLedgerAdminApi;
  private final TierChangeRequestAdminApi tierChangeRequestAdminApi;
  private final AdminRepository adminRepository;
  private final MtTerminalHostClient mtTerminalHostClient;
  private final KafkaConsumerLagService kafkaConsumerLagService;
  private final AuditLogRepository auditLogRepository;
  private final ObjectMapper objectMapper;
  private final AdminCopyRelationshipApi adminCopyRelationshipApi;

  /** System Health's Copy Engine throughput/error-rate window — see {@link #getSystemHealth}. */
  private static final Duration COPY_ENGINE_WINDOW = Duration.ofMinutes(15);

  public AdminController(
      ImpersonationApi impersonationApi,
      BrokerAccountLookupApi brokerAccountLookupApi,
      UserProvisioningApi userProvisioningApi,
      UserAdminApi userAdminApi,
      FeeLedgerAdminApi feeLedgerAdminApi,
      TierChangeRequestAdminApi tierChangeRequestAdminApi,
      AdminRepository adminRepository,
      MtTerminalHostClient mtTerminalHostClient,
      KafkaConsumerLagService kafkaConsumerLagService,
      AuditLogRepository auditLogRepository,
      ObjectMapper objectMapper,
      AdminCopyRelationshipApi adminCopyRelationshipApi) {
    this.impersonationApi = impersonationApi;
    this.brokerAccountLookupApi = brokerAccountLookupApi;
    this.userProvisioningApi = userProvisioningApi;
    this.userAdminApi = userAdminApi;
    this.feeLedgerAdminApi = feeLedgerAdminApi;
    this.tierChangeRequestAdminApi = tierChangeRequestAdminApi;
    this.adminRepository = adminRepository;
    this.mtTerminalHostClient = mtTerminalHostClient;
    this.kafkaConsumerLagService = kafkaConsumerLagService;
    this.auditLogRepository = auditLogRepository;
    this.objectMapper = objectMapper;
    this.adminCopyRelationshipApi = adminCopyRelationshipApi;
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
   *
   * <p>Bugfix follow-up — {@code status} is the Users page's own status filter (beside the
   * name/email search box): {@code ACTIVE}/{@code SUSPENDED}/{@code DELETED}, or blank/absent for
   * the default view (every status except DELETED — see {@code UserRepository#search}'s own Javadoc
   * for why DELETED is never shown unless explicitly filtered for).
   */
  @GetMapping("/api/v1/admin/users")
  @PreAuthorize("hasAnyRole('ADMIN','SUPPORT')")
  public List<UserView> searchUsers(
      @RequestParam(required = false, defaultValue = "") String search,
      @RequestParam(required = false, defaultValue = "") String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int pageSize) {
    return userAdminApi.search(search, status, page, pageSize);
  }

  /**
   * TICKET-117 — admin user detail, including linked broker accounts (via {@link
   * BrokerAccountLookupApi#listForUser}) with each account's real {@code connectionStatus}/{@code
   * lastHealthCheckAt} — the whole reason this endpoint exists rather than just reusing {@link
   * #searchUsers}'s summary rows.
   *
   * <p>Bugfix follow-up — {@code mtTerminals} lets support/admin jump straight from a specific
   * user's MT4/MT5 broker accounts to their live pod status, instead of the only previous path
   * (manually eyeball-matching broker type + login against the unrelated, account-agnostic
   * system-health page). Scoped to just this user's own MT4/MT5 accounts, reusing the exact same
   * {@link TerminalHealthView} shape system-health's own MT terminals section returns, so the
   * frontend can render both with one component. mt-terminal-host is only ever called when this
   * user actually has an MT4/MT5 account — most users are CTRADER-only, and there's no reason to
   * pay that outbound call on every single user-detail page view.
   */
  @GetMapping("/api/v1/admin/users/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','SUPPORT')")
  public UserDetailResponse getUser(@PathVariable UUID id) {
    UserView user = userAdminApi.getUser(id);
    List<BrokerAccountView> brokerAccounts = brokerAccountLookupApi.listForUser(id);
    MtTerminalsSection mtTerminals = buildMtTerminalsSectionForUser(brokerAccounts);
    return new UserDetailResponse(user, brokerAccounts, mtTerminals);
  }

  /**
   * #421 — a SUPER_ADMIN/ADMIN can create a real {@code copy_relationships} row directly, without
   * the Master sending an invite or the Follower requesting one. ADMIN+SUPER_ADMIN — same "grants a
   * real capability" class of action {@link #approveTierChangeRequest} already establishes for
   * SUPER_ADMIN. The Master is identified by email (resolved to a user id here, before ever
   * reaching {@code trading}) rather than requiring a separate master-search UI/ endpoint.
   */
  @PostMapping("/api/v1/admin/users/{followerId}/copy-relationships")
  @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
  public LinkFollowerToMasterResponse linkFollowerToMaster(
      @PathVariable UUID followerId,
      @RequestBody LinkFollowerToMasterRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    UUID actingAdminId = currentUserId(jwt);
    UserView masterUser =
        userAdminApi
            .findByEmail(request.masterEmail())
            .orElseThrow(
                () -> new NoSuchElementException("No such user: " + request.masterEmail()));
    LinkedCopyRelationshipView view =
        adminCopyRelationshipApi.linkFollowerToMaster(
            followerId, masterUser.id(), request.followerBrokerAccountId());
    auditLogRepository.insert(
        actingAdminId,
        "ADMIN",
        "COPY_RELATIONSHIP_ADMIN_LINKED",
        "COPY_RELATIONSHIP",
        view.id().toString(),
        objectMapper.writeValueAsString(
            Map.of(
                "followerUserId", followerId.toString(),
                "masterUserId", masterUser.id().toString(),
                "status", view.status())));
    return new LinkFollowerToMasterResponse(view.id(), view.status(), view.masterDisplayName());
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
   * TICKET-117 bugfix — a real delete, alongside suspend. Sets {@code users.status = 'DELETED'}
   * (the third real value in the CHECK constraint, 002-identity-access.sql) via the same {@code
   * updateStatus} path suspend/reinstate already use — blocked from login/refresh at {@code
   * AuthService#issueNewSession} exactly like SUSPENDED is, no separate enforcement needed. Not a
   * hard row delete: the account, its audit trail, and any FK-referencing rows (broker accounts,
   * copy relationships, audit_log) all stay intact — "deleted" here means deactivated for real,
   * matching how every other account-state transition in this codebase already works.
   */
  @DeleteMapping("/api/v1/admin/users/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public UserView deleteUser(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    UUID actingAdminId = currentUserId(jwt);
    userAdminApi.updateStatus(id, "DELETED");
    auditLogRepository.insert(actingAdminId, "ADMIN", "USER_DELETED", "USER", id.toString(), null);
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
   * TICKET-122 — the pending-review queue. ADMIN+SUPPORT+SUPER_ADMIN can view (same "SUPPORT can
   * view/assist but not action" split every other admin-view endpoint here uses) — only {@link
   * #approveTierChangeRequest}/{@link #rejectTierChangeRequest} are role-granting and restricted
   * further.
   */
  @GetMapping("/api/v1/admin/tier-change-requests")
  @PreAuthorize("hasAnyRole('ADMIN','SUPPORT','SUPER_ADMIN')")
  public List<TierChangeRequestView> listTierChangeRequests(
      @RequestParam(defaultValue = "PENDING") String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int pageSize) {
    return tierChangeRequestAdminApi.listByStatus(status, page, pageSize);
  }

  @GetMapping("/api/v1/admin/tier-change-requests/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','SUPPORT','SUPER_ADMIN')")
  public TierChangeRequestView getTierChangeRequest(@PathVariable UUID id) {
    return tierChangeRequestAdminApi.getDetail(id);
  }

  /**
   * TICKET-122 — grants {@code MASTER}/{@code FOLLOWER} (a real privilege elevation), so ADMIN and
   * SUPER_ADMIN only, not SUPPORT — same "SUPPORT cannot action platform state/financial ledgers"
   * distinction {@code /ledger-adjustments} and {@link #resolveFeeLedgerDispute} both already
   * establish. This is also the first real authorization check ever written against {@code
   * SUPER_ADMIN} (TICKET-114 seeded the role with no consumer wired up yet) — deliberately included
   * here alongside ADMIN rather than SUPER_ADMIN-only, matching the ticket's own summary line
   * ("reviewed and approved or rejected by an Admin or Super Admin").
   */
  @PostMapping("/api/v1/admin/tier-change-requests/{id}/approve")
  @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
  public TierChangeRequestView approveTierChangeRequest(
      @PathVariable UUID id,
      @RequestBody TierChangeDecisionRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    UUID actingAdminId = currentUserId(jwt);
    TierChangeRequestView view =
        tierChangeRequestAdminApi.approve(id, actingAdminId, request.reason());
    auditLogRepository.insert(
        actingAdminId,
        "ADMIN",
        "TIER_CHANGE_REQUEST_APPROVED",
        "TIER_CHANGE_REQUEST",
        id.toString(),
        objectMapper.writeValueAsString(Map.of("targetRole", view.targetRole())));
    return view;
  }

  @PostMapping("/api/v1/admin/tier-change-requests/{id}/reject")
  @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
  public TierChangeRequestView rejectTierChangeRequest(
      @PathVariable UUID id,
      @RequestBody TierChangeDecisionRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    UUID actingAdminId = currentUserId(jwt);
    TierChangeRequestView view =
        tierChangeRequestAdminApi.reject(id, actingAdminId, request.reason());
    auditLogRepository.insert(
        actingAdminId,
        "ADMIN",
        "TIER_CHANGE_REQUEST_REJECTED",
        "TIER_CHANGE_REQUEST",
        id.toString(),
        objectMapper.writeValueAsString(
            Map.of("reason", request.reason() == null ? "" : request.reason())));
    return view;
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
    MtTerminalsSection mtTerminals = buildMtTerminalsSection();
    return new SystemHealthResponse(
        brokerConnections, copyEngine, driftLastHour, consumerLag, mtTerminals);
  }

  /**
   * TICKET-123 — joins every linked MT4/MT5 {@code broker_accounts} row against mt-terminal-host's
   * live pod statuses, so "no pod at all" (never provisioned, or torn down) is distinguishable from
   * "pod exists but unhealthy" — both are real, different failure modes an Admin/Support user needs
   * to tell apart (this ticket's own scope note). {@code reachable=false} (mt-terminal-host itself
   * couldn't be reached) is a THIRD, distinct outcome from either of those — never collapsed into a
   * fabricated "zero terminals" empty list.
   */
  private MtTerminalsSection buildMtTerminalsSection() {
    List<MtBrokerAccountRef> accounts = adminRepository.listMtBrokerAccounts();
    Optional<List<TerminalStatus>> podStatuses = mtTerminalHostClient.listTerminalStatuses();
    if (podStatuses.isEmpty()) {
      return new MtTerminalsSection(false, List.of());
    }
    Map<String, TerminalStatus> byAccountId = indexByAccountId(podStatuses.get());
    List<TerminalHealthView> views =
        accounts.stream()
            .map(
                account ->
                    toTerminalHealthView(
                        account.id(),
                        account.brokerType(),
                        account.brokerAccountLogin(),
                        account.connectionStatus(),
                        byAccountId))
            .toList();
    return new MtTerminalsSection(true, views);
  }

  /**
   * Bugfix follow-up — the per-user counterpart to {@link #buildMtTerminalsSection()}, called from
   * {@link #getUser}. {@code reachable=true} with an empty {@code terminals} list means this user
   * genuinely has no MT4/MT5 accounts (mt-terminal-host was never even called) — distinct from
   * {@code reachable=false}, which means this user DOES have one but mt-terminal-host itself
   * couldn't be reached, same distinction {@link #buildMtTerminalsSection()} already makes.
   */
  private MtTerminalsSection buildMtTerminalsSectionForUser(
      List<BrokerAccountView> brokerAccounts) {
    List<BrokerAccountView> mtAccounts =
        brokerAccounts.stream()
            .filter(a -> "MT4".equals(a.brokerType()) || "MT5".equals(a.brokerType()))
            .toList();
    if (mtAccounts.isEmpty()) {
      return new MtTerminalsSection(true, List.of());
    }
    Optional<List<TerminalStatus>> podStatuses = mtTerminalHostClient.listTerminalStatuses();
    if (podStatuses.isEmpty()) {
      return new MtTerminalsSection(false, List.of());
    }
    Map<String, TerminalStatus> byAccountId = indexByAccountId(podStatuses.get());
    List<TerminalHealthView> views =
        mtAccounts.stream()
            .map(
                account ->
                    toTerminalHealthView(
                        account.id(),
                        account.brokerType(),
                        account.brokerAccountLogin(),
                        account.connectionStatus(),
                        byAccountId))
            .toList();
    return new MtTerminalsSection(true, views);
  }

  private static Map<String, TerminalStatus> indexByAccountId(List<TerminalStatus> podStatuses) {
    Map<String, TerminalStatus> byAccountId = new HashMap<>();
    for (TerminalStatus status : podStatuses) {
      byAccountId.put(status.brokerAccountId(), status);
    }
    return byAccountId;
  }

  private static TerminalHealthView toTerminalHealthView(
      UUID accountId,
      String brokerType,
      String brokerAccountLogin,
      String connectionStatus,
      Map<String, TerminalStatus> byAccountId) {
    TerminalStatus pod = byAccountId.get(accountId.toString());
    return new TerminalHealthView(
        accountId,
        brokerType,
        brokerAccountLogin,
        connectionStatus,
        pod != null,
        pod == null ? null : pod.phase(),
        pod == null ? null : pod.ready(),
        pod == null ? null : pod.restartCount(),
        pod == null ? null : pod.waitingReason(),
        pod == null ? null : pod.lastTransitionTime());
  }

  private UUID currentUserId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  public record LedgerAdjustmentRequest(
      String targetType, String targetId, BigDecimal amount, String reason) {}

  public record ProvisionUserRequest(
      String email, String password, String displayName, String role) {}

  public record TierChangeDecisionRequest(String reason) {}

  public record ProvisionUserResponse(UUID id) {}

  public record AuditLogPageResponse(
      List<AuditLogRepository.AuditLogEntry> entries, long total, int page, int pageSize) {}

  public record UserDetailResponse(
      UserView user, List<BrokerAccountView> brokerAccounts, MtTerminalsSection mtTerminals) {}

  public record LinkFollowerToMasterRequest(String masterEmail, UUID followerBrokerAccountId) {}

  public record LinkFollowerToMasterResponse(
      UUID copyRelationshipId, String status, String masterDisplayName) {}

  public record FeeLedgerDetailResponse(
      FeeLedgerDetailView ledger, List<UnderlyingTradeView> trades) {}

  public record DisputeRequest(String reason) {}

  public record ResolveRequest(String resolution, String note, BigDecimal adjustedAmount) {}

  public record CopyEngineHealth(int windowMinutes, long tradesInWindow, long failedInWindow) {}

  /**
   * TICKET-123 — one MT4/MT5 {@code broker_accounts} row joined against its live terminal pod (if
   * any). {@code podProvisioned=false} means no live pod exists for this account at all (never
   * provisioned, or torn down) — every {@code pod*} field is null in that case, not a fabricated
   * "unhealthy" value.
   */
  public record TerminalHealthView(
      UUID brokerAccountId,
      String brokerType,
      String brokerAccountLogin,
      String connectionStatus,
      boolean podProvisioned,
      String podPhase,
      Boolean podReady,
      Integer podRestartCount,
      String podWaitingReason,
      String podLastTransitionTime) {}

  /**
   * {@code reachable=false} means mt-terminal-host itself couldn't be reached (a real, distinct
   * failure mode from "reachable, zero terminals" — {@code terminals} is empty in both cases, so
   * callers must check {@code reachable} first, never infer service health from list emptiness).
   */
  public record MtTerminalsSection(boolean reachable, List<TerminalHealthView> terminals) {}

  public record SystemHealthResponse(
      List<BrokerConnectionCount> brokerConnections,
      CopyEngineHealth copyEngine,
      long reconciliationDriftLastHour,
      List<ConsumerGroupLag> kafkaConsumerLag,
      MtTerminalsSection mtTerminals) {}
}

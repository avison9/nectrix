package com.nectrix.coreapp.trading.web;

import com.nectrix.coreapp.trading.domain.CopiedTrade;
import com.nectrix.coreapp.trading.domain.CopyRelationship;
import com.nectrix.coreapp.trading.domain.MoneyManagementProfile;
import com.nectrix.coreapp.trading.domain.RiskProfile;
import com.nectrix.coreapp.trading.repository.CopiedTradeRepository;
import com.nectrix.coreapp.trading.repository.MoneyManagementProfileRepository;
import com.nectrix.coreapp.trading.repository.RiskProfileRepository;
import com.nectrix.coreapp.trading.service.CopyRelationshipNotFoundException;
import com.nectrix.coreapp.trading.service.CopyRelationshipService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/14-api-specification.md §14.5's response shape nests moneyManagementProfile/riskProfile —
 * composed here from {@code CopyRelationship}'s bare FK ids plus the two sibling repositories
 * already in this module (TICKET-104/105), rather than widening the domain record itself.
 */
@RestController
public class CopyRelationshipController {

  private final CopyRelationshipService service;
  private final MoneyManagementProfileRepository moneyManagementProfileRepository;
  private final RiskProfileRepository riskProfileRepository;
  private final CopiedTradeRepository copiedTradeRepository;

  public CopyRelationshipController(
      CopyRelationshipService service,
      MoneyManagementProfileRepository moneyManagementProfileRepository,
      RiskProfileRepository riskProfileRepository,
      CopiedTradeRepository copiedTradeRepository) {
    this.service = service;
    this.moneyManagementProfileRepository = moneyManagementProfileRepository;
    this.riskProfileRepository = riskProfileRepository;
    this.copiedTradeRepository = copiedTradeRepository;
  }

  @GetMapping("/api/v1/copy-relationships")
  public List<CopyRelationshipView> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "follower") String role,
      @RequestParam(required = false) String status) {
    return service.listForUser(currentUserId(jwt), role, status).stream()
        .map(this::toView)
        .toList();
  }

  @GetMapping("/api/v1/copy-relationships/{id}")
  public CopyRelationshipView getById(@PathVariable UUID id) {
    return toView(service.getCopyRelationship(id));
  }

  @PatchMapping("/api/v1/copy-relationships/{id}")
  public CopyRelationshipView patch(@PathVariable UUID id, @RequestBody PatchRequest request) {
    // Explicit fetch-then-check-then-mutate — see CopyRelationshipService's own Javadoc.
    CopyRelationship existing = service.getCopyRelationship(id);
    return toView(
        service.updateSettings(
            existing, request.moneyManagementProfileId(), request.riskProfileId()));
  }

  /**
   * TICKET-116 — edits the relationship's money-management/risk profile rows IN PLACE (full-row
   * update via each repository's own {@code update} method), distinct from {@link #patch}'s "swap
   * to a different existing profile id" shape. Matches {@code
   * MoneyManagementProfileRepository#delete}'s own documented "superseded via update, not
   * delete+recreate" 1:1 cardinality with a relationship. Drawdown pause/close-all percentages
   * aren't part of this form — {@code RiskProfileRepository#update} deliberately doesn't touch
   * those columns (TICKET-108's own scope, see that repository's Javadoc), so they stay read-only
   * here too.
   */
  @PatchMapping("/api/v1/copy-relationships/{id}/copy-settings")
  public CopyRelationshipView updateCopySettings(
      @PathVariable UUID id, @RequestBody CopySettingsRequest request) {
    CopyRelationship existing = service.getCopyRelationship(id); // ownership-check gate
    moneyManagementProfileRepository.update(
        existing.moneyManagementProfileId(),
        request.method(),
        request.fixedLotSize(),
        request.multiplier(),
        request.riskPercent(),
        null, // customFormulaExpr — not part of this form
        request.roundingMode());
    riskProfileRepository.update(
        existing.riskProfileId(),
        request.maxLotPerTrade(),
        request.maxOpenPositions(),
        null, // maxExposurePerSymbolLots — not part of this form
        null, // maxTotalExposureLots — not part of this form
        request.maxSlippagePips());
    return toView(service.getCopyRelationship(id));
  }

  @PostMapping("/api/v1/copy-relationships/{id}/acknowledge-risk")
  public CopyRelationshipView acknowledgeRisk(@PathVariable UUID id) {
    CopyRelationship existing = service.getCopyRelationship(id);
    return toView(service.acknowledgeRisk(existing));
  }

  @PostMapping("/api/v1/copy-relationships/{id}/sign-agreement")
  public CopyRelationshipView signAgreement(@PathVariable UUID id) {
    CopyRelationship existing = service.getCopyRelationship(id);
    return toView(service.signAgreement(existing));
  }

  @PostMapping("/api/v1/copy-relationships/{id}/pause")
  public CopyRelationshipView pause(@PathVariable UUID id) {
    CopyRelationship existing = service.getCopyRelationship(id);
    return toView(service.pause(existing));
  }

  @PostMapping("/api/v1/copy-relationships/{id}/resume")
  public CopyRelationshipView resume(@PathVariable UUID id) {
    CopyRelationship existing = service.getCopyRelationship(id);
    return toView(service.resume(existing));
  }

  @PostMapping("/api/v1/copy-relationships/{id}/stop")
  public CopyRelationshipView stop(@PathVariable UUID id) {
    CopyRelationship existing = service.getCopyRelationship(id);
    return toView(service.stop(existing));
  }

  /**
   * docs/14-api-specification.md §14.5's trades-history view — read-only, paginated newest-first
   * (same convention as AdminController's Audit Log viewer). {@code getCopyRelationship} first is
   * what actually enforces ownership here (its own {@code @PostAuthorize}) — this endpoint has no
   * ownership check of its own beyond that.
   */
  @GetMapping("/api/v1/copy-relationships/{id}/trades")
  public TradesPage trades(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    service.getCopyRelationship(id);
    List<CopiedTrade> trades = copiedTradeRepository.findPage(id, page, pageSize);
    long total = copiedTradeRepository.count(id);
    return new TradesPage(trades, total, page, pageSize);
  }

  /**
   * TICKET-116 — cross-relationship trade history (the mock's own Trade History screen spans every
   * relationship the caller has, not just one). Ownership is enforced entirely at the SQL layer
   * (never a bare {@code findAll}) — same {@code role="follower"|"master"} shape {@link
   * CopyRelationshipService#listForUser} already uses, not a per-row {@code @PostAuthorize}
   * (there's no single {@code CopyRelationship} to check against here). {@code relationshipId}, if
   * given, further narrows to one relationship without changing which ones the caller is allowed to
   * see — a relationship outside the caller's own set simply yields zero rows, not a 403/404 (same
   * "narrowing filter" shape {@code status} already has).
   */
  @GetMapping("/api/v1/copy-relationships/trades")
  public TradesPage allTrades(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "follower") String role,
      @RequestParam(required = false) String symbol,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID relationshipId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    UUID userId = currentUserId(jwt);
    List<CopiedTrade> trades =
        copiedTradeRepository.findPageForUser(
            userId, role, symbol, from, to, status, relationshipId, page, pageSize);
    long total =
        copiedTradeRepository.countForUser(userId, role, symbol, from, to, status, relationshipId);
    return new TradesPage(trades, total, page, pageSize);
  }

  private CopyRelationshipView toView(CopyRelationship cr) {
    MoneyManagementProfile mm =
        moneyManagementProfileRepository
            .findById(cr.moneyManagementProfileId())
            .orElseThrow(CopyRelationshipNotFoundException::new);
    RiskProfile risk =
        riskProfileRepository
            .findById(cr.riskProfileId())
            .orElseThrow(CopyRelationshipNotFoundException::new);
    return new CopyRelationshipView(
        cr.id(),
        cr.masterProfileId(),
        cr.followerBrokerAccountId(),
        cr.status(),
        new MoneyManagementProfileView(
            mm.method(), mm.fixedLotSize(), mm.multiplier(), mm.riskPercent(), mm.roundingMode()),
        new RiskProfileView(
            risk.maxLotPerTrade(),
            risk.maxOpenPositions(),
            risk.maxSlippagePips(),
            risk.drawdownPausePct(),
            risk.drawdownCloseAllPct()),
        cr.copyDirection(),
        cr.feeCollectionMethod(),
        cr.originatingInvitationId(),
        cr.originatingFollowRequestId(),
        cr.highWaterMark(),
        cr.createdAt());
  }

  private UUID currentUserId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  public record PatchRequest(
      UUID moneyManagementProfileId, UUID riskProfileId, BigDecimal allocationWeight) {}

  /** TICKET-116 — {@link #updateCopySettings}'s own request shape; always a full-object submit. */
  public record CopySettingsRequest(
      String method,
      BigDecimal fixedLotSize,
      BigDecimal multiplier,
      BigDecimal riskPercent,
      String roundingMode,
      BigDecimal maxLotPerTrade,
      Integer maxOpenPositions,
      BigDecimal maxSlippagePips) {}

  public record MoneyManagementProfileView(
      String method,
      BigDecimal fixedLotSize,
      BigDecimal multiplier,
      BigDecimal riskPercent,
      String roundingMode) {}

  public record RiskProfileView(
      BigDecimal maxLotPerTrade,
      Integer maxOpenPositions,
      BigDecimal maxSlippagePips,
      BigDecimal drawdownPausePct,
      BigDecimal drawdownCloseAllPct) {}

  public record CopyRelationshipView(
      UUID id,
      UUID masterProfileId,
      UUID followerBrokerAccountId,
      String status,
      MoneyManagementProfileView moneyManagementProfile,
      RiskProfileView riskProfile,
      String copyDirection,
      String feeCollectionMethod,
      UUID originatingInvitationId,
      UUID originatingFollowRequestId,
      BigDecimal highWaterMark,
      Instant createdAt) {}

  public record TradesPage(List<CopiedTrade> trades, long total, int page, int pageSize) {}
}

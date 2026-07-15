package com.nectrix.coreapp.trading.service;

import com.nectrix.coreapp.trading.domain.CopyRelationship;
import com.nectrix.coreapp.trading.repository.CopyRelationshipRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.stereotype.Service;

/**
 * The {@code CopyRelationship} state machine (TICKET-111):
 *
 * <pre>
 * PENDING_RISK_ACK --acknowledge-risk--> [PENDING_AGREEMENT if BROKER_PARTNERSHIP, else ACTIVE]
 * PENDING_AGREEMENT --sign-agreement--> ACTIVE
 * ACTIVE &lt;--pause/resume--&gt; PAUSED
 * ACTIVE or PAUSED --stop--&gt; STOPPED (terminal)
 * </pre>
 *
 * Every transition is validated against the CURRENT status server-side (AC2/AC3 — not just a
 * client-side UI guard) before the repository is touched. {@code /stop}'s actual force-close of
 * open {@code copied_trades} (AC4) happens asynchronously in apps/copy-engine (a new ticker-based
 * poller reusing its existing {@code forceCloseAllOpenPositions}), not synchronously in this call —
 * this method only owns the status transition itself.
 *
 * <p>Ownership scope (a deliberate narrowing, not an oversight): this ticket's actual UI is
 * follower-facing only (a Master managing a specific follower's relationship is Admin-Portal
 * capability list territory docs/12-analytics-notifications-admin.md §12.4 defers to a later
 * ticket) — so {@code @PostAuthorize} checks {@code followerUserId} (+ staff bypass), not a
 * master-side lookup.
 */
@Service
public class CopyRelationshipService {

  private final CopyRelationshipRepository repository;

  public CopyRelationshipService(CopyRelationshipRepository repository) {
    this.repository = repository;
  }

  @PostAuthorize("@perms.isOwnerOrStaff(authentication, returnObject.followerUserId())")
  public CopyRelationship getCopyRelationship(UUID id) {
    return repository.findById(id).orElseThrow(CopyRelationshipNotFoundException::new);
  }

  /** List endpoint's own query is scoped to the caller at the SQL layer — never a bare findAll. */
  public List<CopyRelationship> listForUser(UUID userId, String role, String status) {
    return repository.findAllForUser(userId, role, status);
  }

  /**
   * {@code existing} must already have passed {@link #getCopyRelationship}'s ownership check — see
   * this class's own Javadoc / {@code BrokerAccountService}'s identical reasoning for why that
   * can't happen inside these mutating methods themselves (self-invocation bypasses the AOP proxy).
   */
  public CopyRelationship acknowledgeRisk(CopyRelationship existing) {
    requireStatus(existing, "PENDING_RISK_ACK", "acknowledge-risk");
    repository.updateRiskAck(existing.id());
    String next =
        "BROKER_PARTNERSHIP".equals(existing.feeCollectionMethod())
            ? "PENDING_AGREEMENT"
            : "ACTIVE";
    repository.updateStatus(existing.id(), next);
    return reload(existing.id());
  }

  public CopyRelationship signAgreement(CopyRelationship existing) {
    requireStatus(existing, "PENDING_AGREEMENT", "sign-agreement");
    repository.updateStatus(existing.id(), "ACTIVE");
    return reload(existing.id());
  }

  public CopyRelationship pause(CopyRelationship existing) {
    requireStatus(existing, "ACTIVE", "pause");
    repository.updateStatus(existing.id(), "PAUSED");
    return reload(existing.id());
  }

  public CopyRelationship resume(CopyRelationship existing) {
    requireStatus(existing, "PAUSED", "resume");
    repository.updateStatus(existing.id(), "ACTIVE");
    return reload(existing.id());
  }

  /**
   * Allowed from any non-terminal status (AC4 doesn't restrict this to ACTIVE only — a follower can
   * back out even before risk-ack/agreement clears). Only an already-STOPPED relationship rejects.
   */
  public CopyRelationship stop(CopyRelationship existing) {
    if ("STOPPED".equals(existing.status())) {
      throw new InvalidCopyRelationshipTransitionException("copy relationship is already STOPPED");
    }
    repository.markStopped(existing.id());
    return reload(existing.id());
  }

  /**
   * {@code allocationWeight} is accepted but ignored — no backing column exists
   * (Phase-2/portfolio-module territory).
   */
  public CopyRelationship updateSettings(
      CopyRelationship existing, UUID moneyManagementProfileId, UUID riskProfileId) {
    repository.updateProfiles(existing.id(), moneyManagementProfileId, riskProfileId);
    return reload(existing.id());
  }

  private void requireStatus(CopyRelationship existing, String required, String action) {
    if (!required.equals(existing.status())) {
      throw new InvalidCopyRelationshipTransitionException(
          action + " requires status=" + required + ", but current status is " + existing.status());
    }
  }

  private CopyRelationship reload(UUID id) {
    return repository.findById(id).orElseThrow(CopyRelationshipNotFoundException::new);
  }
}

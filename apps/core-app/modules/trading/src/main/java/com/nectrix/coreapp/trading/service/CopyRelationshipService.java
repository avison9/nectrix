package com.nectrix.coreapp.trading.service;

import com.nectrix.coreapp.trading.domain.CopyRelationship;
import com.nectrix.coreapp.trading.domain.ManagementAgreement;
import com.nectrix.coreapp.trading.repository.CopyRelationshipRepository;
import com.nectrix.coreapp.trading.repository.ManagementAgreementRepository;
import com.nectrix.coreapp.trading.storage.AgreementDocumentStorageClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
  private final CopyRelationshipUpdatePublisher updatePublisher;
  private final ManagementAgreementRepository managementAgreementRepository;
  private final AgreementDocumentStorageClient documentStorageClient;

  public CopyRelationshipService(
      CopyRelationshipRepository repository,
      CopyRelationshipUpdatePublisher updatePublisher,
      ManagementAgreementRepository managementAgreementRepository,
      AgreementDocumentStorageClient documentStorageClient) {
    this.repository = repository;
    this.updatePublisher = updatePublisher;
    this.managementAgreementRepository = managementAgreementRepository;
    this.documentStorageClient = documentStorageClient;
  }

  @PostAuthorize("@perms.isOwnerOrStaff(authentication, returnObject.followerUserId())")
  public CopyRelationship getCopyRelationship(UUID id) {
    return repository.findById(id).orElseThrow(CopyRelationshipNotFoundException::new);
  }

  /**
   * Feature — the Master-side counterpart to {@link #getCopyRelationship}: read-only, ownership
   * checked against the MASTER side ({@code masterProfileId}'s owner) instead of the follower —
   * deliberately a SEPARATE method, not a widened {@code @PostAuthorize} on {@link
   * #getCopyRelationship} itself, since every mutation endpoint (pause/resume/stop/patch/etc.)
   * reuses that method as its own fetch-then-check gate; widening it would have let a Master call
   * Follower-only mutations too.
   */
  @PostAuthorize(
      "@perms.isOwnerOrStaff(authentication,"
          + " @masterProfileOwnership.ownerUserId(returnObject.masterProfileId()))")
  public CopyRelationship getCopyRelationshipForMaster(UUID id) {
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

  /**
   * TICKET-120 — the affirmative "I have read and agree" click IS the signature (no third-party
   * e-signature provider, see this ticket's own out-of-scope note); {@code callerUserId} (the
   * Follower, already ownership-checked via {@link #getCopyRelationship}) plus the server's own
   * timestamp stand in for a signature reference. The document is generated and durably uploaded to
   * blob storage BEFORE the relationship ever reaches {@code ACTIVE} — if the upload fails, nothing
   * below has run yet, so a relationship can never end up {@code ACTIVE} without a real,
   * retrievable agreement document behind it.
   */
  public CopyRelationship signAgreement(CopyRelationship existing, UUID callerUserId) {
    requireStatus(existing, "PENDING_AGREEMENT", "sign-agreement");
    Instant signedAt = Instant.now();
    String documentKey = "agreements/" + existing.id() + "/" + signedAt + ".txt";
    String document = renderAgreementDocument(existing, callerUserId, signedAt);
    documentStorageClient.putObject(documentKey, document.getBytes(StandardCharsets.UTF_8));
    managementAgreementRepository.insertSigned(
        existing.id(), documentKey, "user:" + callerUserId + "@" + signedAt);
    repository.updateStatus(existing.id(), "ACTIVE");
    return reload(existing.id());
  }

  /**
   * AC2 — never the raw document, always a short-lived signed URL (see {@code
   * AgreementDocumentStorageClient#presignedGetUrl}'s own Javadoc).
   *
   * @throws ManagementAgreementNotFoundException if this relationship hasn't been signed yet.
   */
  public ManagementAgreementView getAgreement(CopyRelationship existing) {
    ManagementAgreement agreement =
        managementAgreementRepository
            .findByCopyRelationshipId(existing.id())
            .orElseThrow(ManagementAgreementNotFoundException::new);
    return new ManagementAgreementView(
        agreement.id(),
        agreement.status(),
        agreement.signedAt(),
        documentStorageClient.presignedGetUrl(agreement.documentObjectKey()).toString());
  }

  private String renderAgreementDocument(
      CopyRelationship relationship, UUID callerUserId, Instant signedAt) {
    return """
        NECTRIX MANAGEMENT AGREEMENT
        ============================
        Copy relationship: %s
        Signed by (Follower user id): %s
        Signed at: %s
        Fee collection method: %s
        Performance fee: %s%%

        By clicking "I have read and agree," the Follower named above authorizes the
        performance fee described here to be reported to their broker and deducted from
        their trading account under the Broker Partnership fee-collection workflow.
        """
        .formatted(
            relationship.id(),
            callerUserId,
            signedAt,
            relationship.feeCollectionMethod(),
            relationship.performanceFeePercent());
  }

  public record ManagementAgreementView(
      UUID id, String status, Instant signedAt, String documentUrl) {}

  public CopyRelationship pause(CopyRelationship existing) {
    requireStatus(existing, "ACTIVE", "pause");
    repository.updateStatus(existing.id(), "PAUSED");
    return reloadAndPublish(existing.id());
  }

  public CopyRelationship resume(CopyRelationship existing) {
    requireStatus(existing, "PAUSED", "resume");
    repository.updateStatus(existing.id(), "ACTIVE");
    return reloadAndPublish(existing.id());
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
    return reloadAndPublish(existing.id());
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

  /**
   * TICKET-116 — pushes the new status onto the {@code copy-relationships.{id}} WS channel
   * (docs/14-api-specification.md §14.11) right after a successful pause/resume/stop transition, a
   * same-request push rather than a Kafka round-trip. Hand-built JSON, not a library: every field
   * is a server-controlled UUID/status-enum value, nothing user-supplied to escape (same reasoning
   * {@code NotificationDispatchService} doesn't need for its own free-text title/body).
   */
  private CopyRelationship reloadAndPublish(UUID id) {
    CopyRelationship reloaded = reload(id);
    String json =
        "{\"channel\":\"copy-relationships\",\"type\":\"status_changed\",\"copyRelationshipId\":\""
            + reloaded.id()
            + "\",\"status\":\""
            + reloaded.status()
            + "\"}";
    updatePublisher.publish(id, json);
    return reloaded;
  }
}

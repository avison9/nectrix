package com.nectrix.coreapp.invitations.service;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.invitations.domain.TierChangeRequest;
import com.nectrix.coreapp.invitations.repository.TierChangeRequestRepository;
import com.nectrix.coreapp.notifications.api.NotificationDispatchApi;
import com.nectrix.coreapp.notifications.domain.NotificationEventTypes;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * TICKET-122 — the request/approval path that lets a self-registered Individual-mode user (TICKET-
 * 114 deliberately grants only the base {@code USER} role, never {@code MASTER}/{@code FOLLOWER})
 * become a Master or Follower without an admin-provisioning or invite-accept path. {@link #submit}
 * is the only self-service entry point; {@link #approve} is the only non-admin-provisioning/
 * non-invite-accept path that ever calls {@link UserProvisioningApi#grantRole} with {@code MASTER}/
 * {@code FOLLOWER} — see that interface's own Javadoc, which names this class explicitly.
 */
@Service
public class TierChangeRequestService {

  /**
   * Server-resolved from {@code targetRole}, never taken from the request body — a client-supplied
   * agreement version would let a caller claim to have accepted a version of the agreement that was
   * never actually shown to them.
   */
  private static final String MASTER_AGREEMENT_VERSION = "master-trading-agreement-v1";

  private static final String FOLLOWER_AGREEMENT_VERSION = "follower-risk-disclosure-v1";

  private final TierChangeRequestRepository repository;
  private final UserProvisioningApi userProvisioningApi;
  private final NotificationDispatchApi notificationDispatchApi;

  public TierChangeRequestService(
      TierChangeRequestRepository repository,
      UserProvisioningApi userProvisioningApi,
      NotificationDispatchApi notificationDispatchApi) {
    this.repository = repository;
    this.userProvisioningApi = userProvisioningApi;
    this.notificationDispatchApi = notificationDispatchApi;
  }

  /**
   * @param callerRoles the caller's {@code roles} JWT claim — same source {@code
   *     IndividualModeCapabilityGuard} already reads, no extra DB round trip needed to know whether
   *     the caller is currently Individual-mode.
   */
  public TierChangeRequest submit(
      UUID userId, String targetRole, List<String> callerRoles, boolean agreementAccepted) {
    if (!"MASTER".equals(targetRole) && !"FOLLOWER".equals(targetRole)) {
      throw new InvalidTierChangeTargetRoleException();
    }
    if (callerRoles != null
        && (callerRoles.contains("MASTER") || callerRoles.contains("FOLLOWER"))) {
      throw new TierChangeAlreadyHasRoleException();
    }
    if (repository.findPendingByUserId(userId).isPresent()) {
      throw new TierChangePendingRequestExistsException();
    }
    if (!agreementAccepted) {
      throw new TierChangeAgreementNotAcceptedException();
    }
    String agreementVersion =
        "MASTER".equals(targetRole) ? MASTER_AGREEMENT_VERSION : FOLLOWER_AGREEMENT_VERSION;
    UUID id = repository.insert(userId, targetRole, agreementVersion, Instant.now());
    return repository.findById(id).orElseThrow();
  }

  /** Backs {@code GET .../me} — the caller's own most recent request, whatever its status. */
  public Optional<TierChangeRequest> getMine(UUID userId) {
    return repository.findLatestByUserId(userId);
  }

  public List<TierChangeRequest> listByStatus(String status, int page, int pageSize) {
    return repository.findByStatus(status, page, pageSize);
  }

  public TierChangeRequest getById(UUID id) {
    return repository.findById(id).orElseThrow(TierChangeRequestNotFoundException::new);
  }

  /**
   * AC5 — the agreement check is re-verified here, not just trusted from submission time (see
   * {@link TierChangeAgreementNotAcceptedException}'s own Javadoc for why this can never actually
   * fire given {@link #submit}'s own guard, but is checked for real anyway).
   */
  public TierChangeRequest approve(UUID requestId, UUID adminUserId, String reason) {
    TierChangeRequest request = getById(requestId);
    if (!"PENDING".equals(request.status())) {
      throw new TierChangeRequestNotPendingException();
    }
    if (request.agreementAcceptedAt() == null) {
      throw new TierChangeAgreementNotAcceptedException();
    }
    userProvisioningApi.grantRole(request.userId(), request.targetRole());
    repository.markApproved(requestId, adminUserId, reason);
    TierChangeRequest updated = getById(requestId);
    notificationDispatchApi.dispatch(
        request.userId(),
        NotificationEventTypes.TIER_CHANGE_REQUEST_DECIDED,
        "Your account tier-change request was approved",
        "Your request to become a "
            + friendlyRole(request.targetRole())
            + " was approved. Sign in again to see your new access.");
    return updated;
  }

  public TierChangeRequest reject(UUID requestId, UUID adminUserId, String reason) {
    TierChangeRequest request = getById(requestId);
    if (!"PENDING".equals(request.status())) {
      throw new TierChangeRequestNotPendingException();
    }
    repository.markRejected(requestId, adminUserId, reason);
    TierChangeRequest updated = getById(requestId);
    String reasonSuffix = (reason == null || reason.isBlank()) ? "" : " Reason: " + reason;
    notificationDispatchApi.dispatch(
        request.userId(),
        NotificationEventTypes.TIER_CHANGE_REQUEST_DECIDED,
        "Your account tier-change request was rejected",
        "Your request to become a "
            + friendlyRole(request.targetRole())
            + " was rejected."
            + reasonSuffix);
    return updated;
  }

  private static String friendlyRole(String role) {
    return "MASTER".equals(role) ? "Master" : "Follower";
  }
}

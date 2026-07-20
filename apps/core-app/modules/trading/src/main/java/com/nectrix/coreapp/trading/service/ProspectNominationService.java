package com.nectrix.coreapp.trading.service;

import com.nectrix.coreapp.invitations.api.InvitationLookupApi;
import com.nectrix.coreapp.invitations.api.InvitationView;
import com.nectrix.coreapp.notifications.api.NotificationDispatchApi;
import com.nectrix.coreapp.notifications.domain.NotificationEventTypes;
import com.nectrix.coreapp.social.api.MasterProfileLookupApi;
import com.nectrix.coreapp.social.api.MasterProfileSummaryView;
import com.nectrix.coreapp.trading.domain.CopyRelationship;
import com.nectrix.coreapp.trading.domain.ProspectNomination;
import com.nectrix.coreapp.trading.repository.CopyRelationshipRepository;
import com.nectrix.coreapp.trading.repository.MasterProfileIdLookupRepository;
import com.nectrix.coreapp.trading.repository.ProspectNominationRepository;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * TICKET-118 follow-up — the "Follower refers a prospect, lands in their Master's inbox, Master
 * sends a real invitation" flow. {@code apps/web}'s {@code /follower/referrals} (nominate) and
 * {@code /inbox} (review) pages both existed as fully inert placeholders miscited to Phase 2's
 * TICKET-207 referral-rewards program, which never actually specified this mechanism — see this
 * class's own migration comment for why {@code follow_requests} (a genuinely different, Phase-2
 * concept) isn't reused here.
 *
 * <p>Sending the actual invitation is deliberately NOT this service's job — the frontend calls the
 * existing {@code POST /master/invitations} endpoint directly with the prospect's email, then
 * {@link #markInvited} just records that this nomination was actioned (linking the resulting
 * invitation id) — no new cross-module coupling into {@code modules:invitations} needed for that
 * step at all.
 */
@Service
public class ProspectNominationService {

  private final ProspectNominationRepository repository;
  private final MasterProfileIdLookupRepository masterProfileIdLookupRepository;
  private final CopyRelationshipRepository copyRelationshipRepository;
  private final MasterProfileLookupApi masterProfileLookupApi;
  private final NotificationDispatchApi notificationDispatchApi;
  private final InvitationLookupApi invitationLookupApi;

  public ProspectNominationService(
      ProspectNominationRepository repository,
      MasterProfileIdLookupRepository masterProfileIdLookupRepository,
      CopyRelationshipRepository copyRelationshipRepository,
      MasterProfileLookupApi masterProfileLookupApi,
      NotificationDispatchApi notificationDispatchApi,
      InvitationLookupApi invitationLookupApi) {
    this.repository = repository;
    this.masterProfileIdLookupRepository = masterProfileIdLookupRepository;
    this.copyRelationshipRepository = copyRelationshipRepository;
    this.masterProfileLookupApi = masterProfileLookupApi;
    this.notificationDispatchApi = notificationDispatchApi;
    this.invitationLookupApi = invitationLookupApi;
  }

  /**
   * Resolves "my master" from the caller's own {@code copy_relationships} row — Phase 1 Followers
   * have exactly one Master (see {@code TICKET-118-invitation-system.md}'s own scope note), so the
   * first relationship found is unambiguous.
   */
  public ProspectNomination nominate(UUID followerUserId, String prospectEmail) {
    List<CopyRelationship> relationships =
        copyRelationshipRepository.findAllForUser(followerUserId, "follower", null);
    if (relationships.isEmpty()) {
      throw new NoMasterToNominateToException();
    }
    UUID masterProfileId = relationships.get(0).masterProfileId();
    UUID id = repository.insert(masterProfileId, followerUserId, prospectEmail);

    MasterProfileSummaryView master = masterProfileLookupApi.getMasterProfile(masterProfileId);
    notificationDispatchApi.dispatch(
        master.userId(),
        NotificationEventTypes.PROSPECT_NOMINATION_RECEIVED,
        "A follower referred a prospect",
        prospectEmail + " was referred by one of your followers — review it in your inbox.");

    return repository.findById(id).orElseThrow(ProspectNominationNotFoundException::new);
  }

  /** {@code status} is an optional filter (null = every nomination for this Master). */
  public List<NominationView> listForMaster(UUID callerUserId, String status) {
    UUID masterProfileId = requireMasterProfileId(callerUserId);
    List<ProspectNomination> nominations =
        repository.findByMasterProfileId(masterProfileId, status);
    Map<UUID, String> displayNames =
        repository.findDisplayNamesByIds(
            nominations.stream()
                .map(ProspectNomination::nominatedByUserId)
                .collect(Collectors.toSet()));
    return nominations.stream()
        .map(n -> new NominationView(n, displayNames.get(n.nominatedByUserId())))
        .toList();
  }

  /**
   * The Follower's own referral history. Resolves a richer, honest status than the raw DB enum:
   * {@code SENT} (still PENDING), {@code INVITED} (Master sent the invite, not yet accepted),
   * {@code JOINED} (the invitation was actually accepted — a real signal, not a guess, via the
   * already-established {@link InvitationLookupApi}), {@code DISMISSED}.
   */
  public List<FollowerNominationView> listForFollower(UUID followerUserId) {
    return repository.findByNominatedByUserId(followerUserId).stream()
        .map(n -> new FollowerNominationView(n, resolveFollowerFacingStatus(n)))
        .toList();
  }

  private String resolveFollowerFacingStatus(ProspectNomination nomination) {
    if (!"INVITED".equals(nomination.status()) || nomination.invitationId() == null) {
      return switch (nomination.status()) {
        case "PENDING" -> "SENT";
        default -> nomination.status(); // DISMISSED
      };
    }
    try {
      InvitationView invitation = invitationLookupApi.getInvitation(nomination.invitationId());
      return "ACCEPTED".equals(invitation.status()) ? "JOINED" : "INVITED";
    } catch (NoSuchElementException e) {
      return "INVITED";
    }
  }

  /**
   * The Master already sent the real invitation (a separate `POST /master/invitations` call) — this
   * just records it.
   */
  public void markInvited(UUID callerUserId, UUID nominationId, UUID invitationId) {
    ProspectNomination nomination = requireOwnedByCaller(callerUserId, nominationId);
    repository.markInvited(nomination.id(), invitationId);
  }

  public void dismiss(UUID callerUserId, UUID nominationId) {
    ProspectNomination nomination = requireOwnedByCaller(callerUserId, nominationId);
    repository.markDismissed(nomination.id());
  }

  private ProspectNomination requireOwnedByCaller(UUID callerUserId, UUID nominationId) {
    UUID masterProfileId = requireMasterProfileId(callerUserId);
    ProspectNomination nomination =
        repository.findById(nominationId).orElseThrow(ProspectNominationNotFoundException::new);
    if (!nomination.masterProfileId().equals(masterProfileId)) {
      throw new ProspectNominationNotFoundException();
    }
    return nomination;
  }

  private UUID requireMasterProfileId(UUID userId) {
    return masterProfileIdLookupRepository
        .findMasterProfileIdForUser(userId)
        .orElseThrow(MasterProfileRequiredException::new);
  }

  public record NominationView(ProspectNomination nomination, String nominatedByDisplayName) {}

  public record FollowerNominationView(
      ProspectNomination nomination, String followerFacingStatus) {}
}

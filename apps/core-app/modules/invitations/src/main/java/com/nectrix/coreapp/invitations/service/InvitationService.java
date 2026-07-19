package com.nectrix.coreapp.invitations.service;

import com.nectrix.coreapp.invitations.config.InvitationsProperties;
import com.nectrix.coreapp.invitations.domain.Invitation;
import com.nectrix.coreapp.invitations.repository.InvitationsRepository;
import com.nectrix.coreapp.invitations.repository.MasterProfileLookupRepository;
import com.nectrix.coreapp.notifications.api.EmailApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * TICKET-118 — invitation creation/listing/revocation (Master-scoped) plus public token validation
 * (accept-screen preview, {@code AcceptInviteController}'s own lookup).
 *
 * <p>Token generation/hashing intentionally copies {@code auth.service.AuthService}'s exact {@code
 * generateOpaqueToken}/{@code hashToken} pair verbatim (SecureRandom 32 bytes -> URL-safe Base64
 * raw token; SHA-256 -> standard Base64 hash, only the hash ever persisted) rather than sharing it
 * — two small, self-contained private methods with no auth-specific dependency, not worth a new
 * cross-module surface just to avoid duplicating ~15 lines.
 */
@Service
public class InvitationService {

  private static final long EXPIRY_DAYS = 7;

  private final InvitationsRepository repository;
  private final MasterProfileLookupRepository masterProfileLookupRepository;
  private final EmailApi emailApi;
  private final InvitationsProperties properties;
  private final SecureRandom secureRandom = new SecureRandom();

  public InvitationService(
      InvitationsRepository repository,
      MasterProfileLookupRepository masterProfileLookupRepository,
      EmailApi emailApi,
      InvitationsProperties properties) {
    this.repository = repository;
    this.masterProfileLookupRepository = masterProfileLookupRepository;
    this.emailApi = emailApi;
    this.properties = properties;
  }

  /**
   * @param callerUserId the authenticated Master issuing this invitation.
   * @return the newly created invitation (its {@code tokenHash} only — the raw token is never
   *     returned from here; it was already embedded in the email this method just sent).
   */
  public Invitation create(
      UUID callerUserId,
      String invitedEmail,
      UUID suggestedBrokerIbLinkId,
      UUID suggestedMoneyManagementProfileId,
      UUID suggestedRiskProfileId) {
    UUID masterProfileId = requireMasterProfileId(callerUserId);
    String rawToken = generateOpaqueToken();
    String tokenHash = hashToken(rawToken);
    Instant expiresAt = Instant.now().plus(EXPIRY_DAYS, ChronoUnit.DAYS);
    UUID id =
        repository.insert(
            masterProfileId,
            invitedEmail,
            tokenHash,
            suggestedBrokerIbLinkId,
            suggestedMoneyManagementProfileId,
            suggestedRiskProfileId,
            callerUserId,
            expiresAt);
    String link = properties.acceptUrlBase() + "?token=" + rawToken;
    emailApi.sendRaw(
        invitedEmail,
        "You've been invited to copy trade on Nectrix",
        "Follow this link to accept your invitation: " + link);
    return repository.findById(id).orElseThrow(InvitationNotFoundException::new);
  }

  public List<Invitation> listForMaster(UUID callerUserId, String status) {
    UUID masterProfileId = requireMasterProfileId(callerUserId);
    return repository.findByMasterProfileId(masterProfileId, status);
  }

  /**
   * No-ops safely if the invitation is already non-{@code PENDING} — repeat revokes aren't an
   * error.
   */
  public void revoke(UUID callerUserId, UUID invitationId) {
    UUID masterProfileId = requireMasterProfileId(callerUserId);
    Invitation invitation =
        repository.findById(invitationId).orElseThrow(InvitationNotFoundException::new);
    if (!invitation.masterProfileId().equals(masterProfileId)) {
      throw new InvitationNotFoundException();
    }
    if (!"PENDING".equals(invitation.status())) {
      return;
    }
    repository.updateStatus(invitationId, "REVOKED");
  }

  /**
   * Hashes {@code rawToken}, looks it up, lazily flips an expired {@code PENDING} row to {@code
   * EXPIRED} before evaluating it. Throws the same {@link InvalidInvitationException} for
   * not-found/expired/revoked/already-accepted — never leaks which case applies (this ticket's own
   * AC).
   */
  public Invitation validateByToken(String rawToken) {
    String tokenHash = hashToken(rawToken);
    Invitation invitation =
        repository.findByTokenHash(tokenHash).orElseThrow(InvalidInvitationException::new);
    if ("PENDING".equals(invitation.status()) && invitation.expiresAt().isBefore(Instant.now())) {
      repository.updateStatus(invitation.id(), "EXPIRED");
      throw new InvalidInvitationException();
    }
    if (!"PENDING".equals(invitation.status())) {
      throw new InvalidInvitationException();
    }
    return invitation;
  }

  /** {@code AcceptInviteController}'s own final step, after resolving/creating the user. */
  public void markAccepted(UUID invitationId, UUID acceptedByUserId) {
    repository.markAccepted(invitationId, acceptedByUserId);
  }

  private UUID requireMasterProfileId(UUID userId) {
    return masterProfileLookupRepository
        .findMasterProfileIdForUser(userId)
        .orElseThrow(MasterProfileRequiredException::new);
  }

  private String generateOpaqueToken() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String hashToken(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}

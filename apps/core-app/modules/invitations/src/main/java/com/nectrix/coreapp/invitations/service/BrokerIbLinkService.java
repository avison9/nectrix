package com.nectrix.coreapp.invitations.service;

import com.nectrix.coreapp.invitations.domain.BrokerIbLink;
import com.nectrix.coreapp.invitations.repository.BrokerIbLinkRepository;
import com.nectrix.coreapp.invitations.repository.MasterProfileLookupRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * TICKET-119 — Broker IB Link creation/listing/deactivation, Master-scoped exactly like {@code
 * InvitationService} (same {@code requireMasterProfileId} ownership-resolution pattern — {@code
 * master_profile_id} is always resolved fresh from the caller's own JWT-derived user id, never
 * accepted from the client).
 */
@Service
public class BrokerIbLinkService {

  private final BrokerIbLinkRepository repository;
  private final MasterProfileLookupRepository masterProfileLookupRepository;

  public BrokerIbLinkService(
      BrokerIbLinkRepository repository,
      MasterProfileLookupRepository masterProfileLookupRepository) {
    this.repository = repository;
    this.masterProfileLookupRepository = masterProfileLookupRepository;
  }

  public BrokerIbLink create(
      UUID callerUserId, String brokerType, String brokerDisplayName, String ibReferralUrlOrCode) {
    UUID masterProfileId = requireMasterProfileId(callerUserId);
    UUID id =
        repository.insert(masterProfileId, brokerType, brokerDisplayName, ibReferralUrlOrCode);
    return repository.findById(id).orElseThrow(BrokerIbLinkNotFoundException::new);
  }

  public List<BrokerIbLink> listForMaster(UUID callerUserId) {
    UUID masterProfileId = requireMasterProfileId(callerUserId);
    return repository.findAllForMaster(masterProfileId);
  }

  /**
   * AC3 — a Master cannot deactivate another Master's link: {@code existing.masterProfileId()} must
   * match the caller's own, or this 404s exactly as if the row didn't exist (never leaking that a
   * differently-owned id is valid).
   */
  public void deactivate(UUID callerUserId, UUID linkId) {
    UUID masterProfileId = requireMasterProfileId(callerUserId);
    BrokerIbLink existing =
        repository.findById(linkId).orElseThrow(BrokerIbLinkNotFoundException::new);
    if (!existing.masterProfileId().equals(masterProfileId)) {
      throw new BrokerIbLinkNotFoundException();
    }
    repository.deactivate(linkId);
  }

  private UUID requireMasterProfileId(UUID userId) {
    return masterProfileLookupRepository
        .findMasterProfileIdForUser(userId)
        .orElseThrow(MasterProfileRequiredException::new);
  }
}

package com.nectrix.coreapp.invitations.api;

import com.nectrix.coreapp.invitations.domain.Invitation;
import com.nectrix.coreapp.invitations.repository.InvitationsRepository;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class InvitationLookupApiImpl implements InvitationLookupApi {

  private final InvitationsRepository repository;

  public InvitationLookupApiImpl(InvitationsRepository repository) {
    this.repository = repository;
  }

  @Override
  public InvitationView getInvitation(UUID id) {
    Invitation invitation =
        repository
            .findById(id)
            .orElseThrow(() -> new NoSuchElementException("No such invitation: " + id));
    return new InvitationView(
        invitation.id(),
        invitation.masterProfileId(),
        invitation.invitedEmail(),
        invitation.status(),
        invitation.suggestedMoneyManagementProfileId(),
        invitation.suggestedRiskProfileId(),
        invitation.acceptedByUserId());
  }
}

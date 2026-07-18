package com.nectrix.coreapp.auth.service;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.auth.repository.UserRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * TICKET-114 — the one deliberate exception to "no self-registration anywhere"
 * (docs/05-domain-model.md §5.0): a self-serve "Individual" account, created with no admin
 * provisioner and no invitation ({@code createdByUserId}/{@code createdViaInvitationId} both null,
 * same nullable contract {@link UserProvisioningApi#createUser} already documents for the bootstrap
 * admin). Grants the base {@code USER} role only — deliberately NOT {@code MASTER}/{@code FOLLOWER}
 * (a self-registered Individual cannot act as either until an admin approves a tier-change request,
 * TICKET-122). Region is not yet collected from the registration form (the mock's own form is just
 * name/email/password) — {@code defaultRegion} is a placeholder pending real geo-IP/jurisdiction
 * logic, not this ticket's concern.
 */
@Service
public class RegistrationService {

  private static final String DEFAULT_REGION = "US";

  private final UserRepository userRepository;
  private final UserProvisioningApi userProvisioningApi;

  public RegistrationService(
      UserRepository userRepository, UserProvisioningApi userProvisioningApi) {
    this.userRepository = userRepository;
    this.userProvisioningApi = userProvisioningApi;
  }

  public UUID register(String email, String rawPassword, String displayName) {
    if (userRepository.findByEmail(email).isPresent()) {
      throw new EmailAlreadyRegisteredException();
    }
    UUID userId =
        userProvisioningApi.createUser(
            email, rawPassword, displayName, null, null, null, DEFAULT_REGION);
    userProvisioningApi.grantRole(userId, "USER");
    return userId;
  }
}

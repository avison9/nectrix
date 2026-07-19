package com.nectrix.coreapp.auth.api;

import com.nectrix.coreapp.auth.domain.User;
import com.nectrix.coreapp.auth.repository.UserRepository;
import com.nectrix.coreapp.auth.service.PasswordService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class UserProvisioningApiImpl implements UserProvisioningApi {

  private final UserRepository userRepository;
  private final PasswordService passwordService;

  public UserProvisioningApiImpl(UserRepository userRepository, PasswordService passwordService) {
    this.userRepository = userRepository;
    this.passwordService = passwordService;
  }

  @Override
  public UUID createUser(
      String email,
      String rawPassword,
      String displayName,
      UUID createdByUserId,
      UUID createdViaInvitationId,
      UUID referredByUserId,
      String region) {
    String passwordHash = rawPassword == null ? null : passwordService.hash(rawPassword);
    return userRepository.insert(
        email,
        passwordHash,
        displayName,
        createdByUserId,
        createdViaInvitationId,
        referredByUserId,
        region);
  }

  @Override
  public void grantRole(UUID userId, String roleName) {
    userRepository.insertUserRole(userId, roleName);
  }

  @Override
  public Optional<UUID> findUserIdByEmail(String email) {
    return userRepository.findByEmail(email).map(User::id);
  }
}

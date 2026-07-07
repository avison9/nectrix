package com.nectrix.coreapp.auth.api;

import com.nectrix.coreapp.auth.domain.User;
import com.nectrix.coreapp.auth.repository.UserRepository;
import com.nectrix.coreapp.auth.service.JwtService;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ImpersonationApiImpl implements ImpersonationApi {

  private final UserRepository userRepository;
  private final JwtService jwtService;

  public ImpersonationApiImpl(UserRepository userRepository, JwtService jwtService) {
    this.userRepository = userRepository;
    this.jwtService = jwtService;
  }

  @Override
  public ImpersonationResult impersonate(UUID targetUserId, UUID actingAdminId) {
    User target =
        userRepository
            .findById(targetUserId)
            .orElseThrow(() -> new NoSuchElementException("No such user: " + targetUserId));
    var roles = userRepository.findRoleNames(targetUserId);
    String accessToken =
        jwtService.issueImpersonationToken(targetUserId, target.email(), roles, actingAdminId);
    return new ImpersonationResult(accessToken, JwtService.ACCESS_TOKEN_TTL_SECONDS);
  }
}

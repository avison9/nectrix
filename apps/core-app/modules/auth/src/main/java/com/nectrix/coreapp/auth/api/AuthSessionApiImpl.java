package com.nectrix.coreapp.auth.api;

import com.nectrix.coreapp.auth.domain.TokenPair;
import com.nectrix.coreapp.auth.service.AuthService;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuthSessionApiImpl implements AuthSessionApi {

  private final AuthService authService;

  public AuthSessionApiImpl(AuthService authService) {
    this.authService = authService;
  }

  @Override
  public TokenPairView issueSession(UUID userId, String deviceInfoJson, String ipAddress) {
    TokenPair pair = authService.issueSessionForExistingUser(userId, deviceInfoJson, ipAddress);
    return new TokenPairView(pair.accessToken(), pair.refreshToken(), pair.expiresIn());
  }
}

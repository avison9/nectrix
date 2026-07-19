package com.nectrix.coreapp.auth.service;

import com.nectrix.coreapp.auth.domain.TokenPair;
import com.nectrix.coreapp.auth.domain.User;
import com.nectrix.coreapp.auth.repository.SessionRepository;
import com.nectrix.coreapp.auth.repository.UserRepository;
import com.nectrix.coreapp.auth.service.oauth.AppleOAuthProvider;
import com.nectrix.coreapp.auth.service.oauth.GoogleOAuthProvider;
import com.nectrix.coreapp.auth.service.oauth.OAuthProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login/refresh/logout/oauth-callback orchestration — the one class that owns the atomic
 * claim-and-rotate refresh-token logic (docs/17-security-architecture.md §17.3).
 */
@Service
public class AuthService {

  /**
   * docs/07-auth-onboarding-broker-linking.md's "sessions" model doesn't specify a refresh-token
   * lifetime — 30 days is a reasonable, common default.
   */
  private static final long REFRESH_TOKEN_TTL_DAYS = 30;

  private final UserRepository userRepository;
  private final SessionRepository sessionRepository;
  private final PasswordService passwordService;
  private final JwtService jwtService;
  private final TwoFactorService twoFactorService;
  private final RateLimiterService rateLimiterService;
  private final GoogleOAuthProvider googleOAuthProvider;
  private final AppleOAuthProvider appleOAuthProvider;
  private final SecureRandom secureRandom = new SecureRandom();

  public AuthService(
      UserRepository userRepository,
      SessionRepository sessionRepository,
      PasswordService passwordService,
      JwtService jwtService,
      TwoFactorService twoFactorService,
      RateLimiterService rateLimiterService,
      GoogleOAuthProvider googleOAuthProvider,
      AppleOAuthProvider appleOAuthProvider) {
    this.userRepository = userRepository;
    this.sessionRepository = sessionRepository;
    this.passwordService = passwordService;
    this.jwtService = jwtService;
    this.twoFactorService = twoFactorService;
    this.rateLimiterService = rateLimiterService;
    this.googleOAuthProvider = googleOAuthProvider;
    this.appleOAuthProvider = appleOAuthProvider;
  }

  public TokenPair login(
      String email, String password, String totpCode, String deviceInfoJson, String ipAddress) {
    if (!rateLimiterService.tryConsume("login:" + email)) {
      throw new RateLimitExceededException();
    }
    User user = userRepository.findByEmail(email).orElseThrow(InvalidCredentialsException::new);
    if (user.passwordHash() == null || !passwordService.matches(password, user.passwordHash())) {
      throw new InvalidCredentialsException();
    }
    if (user.twoFactorEnabled()) {
      if (totpCode == null || totpCode.isBlank()) {
        throw new TwoFactorRequiredException();
      }
      if (!rateLimiterService.tryConsume("2fa-verify:" + user.id())) {
        throw new RateLimitExceededException();
      }
      if (!twoFactorService.verifyLoginCode(user, totpCode)) {
        throw new InvalidCredentialsException();
      }
    }
    return issueNewSession(user, deviceInfoJson, ipAddress);
  }

  /**
   * Atomic claim-and-rotate: exactly one caller ever "wins" a given refresh token, forking two live
   * sessions from one rotation is impossible (see SessionRepository#claimForRotation's own doc
   * comment for why). Zero rows from the claim requires a fallback lookup to tell an ordinary
   * expired session apart from a genuine reuse-detected replay — collapsing that distinction would
   * make the two indistinguishable and mass-revoke a user's sessions over a merely stale token.
   *
   * <p>{@code noRollbackFor} is required, not cosmetic: the reuse-detected branch below calls
   * {@code revokeAllForUser} and then throws {@link InvalidRefreshTokenException} to signal the
   * caller — without this, Spring's default rollback-on-RuntimeException would silently undo that
   * exact revocation once the method exits via the exception, defeating the whole mass-revoke
   * (caught by AC4's own integration test, which checks the DB directly, not just the HTTP status).
   */
  @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
  public TokenPair refresh(String refreshToken, String deviceInfoJson, String ipAddress) {
    String hash = hashToken(refreshToken);
    Optional<UUID> claimedUserId = sessionRepository.claimForRotation(hash);
    if (claimedUserId.isPresent()) {
      User user =
          userRepository.findById(claimedUserId.get()).orElseThrow(NoSuchElementException::new);
      return issueNewSession(user, deviceInfoJson, ipAddress);
    }

    var existing = sessionRepository.findByRefreshTokenHash(hash);
    if (existing.isEmpty()) {
      throw new InvalidRefreshTokenException(); // never existed
    }
    if (existing.get().revokedAt() != null) {
      // Reuse of an already-rotated/revoked token — treat as a signal of token
      // theft (docs/17-security-architecture.md §17.3) and revoke everything.
      // Accepted trade-off: a legitimate client retry after a network timeout
      // (server already committed the rotation, client retries with the
      // now-stale token) hits this exact same branch as a real replay attack.
      // Inherent to opaque-token rotation without a grace window; the
      // ticket's own AC ("reusing an already-rotated token revokes all
      // sessions") drove this choice — not a bug to "fix" later.
      sessionRepository.revokeAllForUser(existing.get().userId(), "REUSE_DETECTED");
      throw new InvalidRefreshTokenException();
    }
    // Not revoked, so the atomic claim's own WHERE clause must have failed on expires_at.
    throw new InvalidRefreshTokenException(); // expired — no mass revocation
  }

  public void logout(String refreshToken) {
    String hash = hashToken(refreshToken);
    sessionRepository
        .findByRefreshTokenHash(hash)
        .ifPresent(s -> sessionRepository.revoke(s.id(), "LOGOUT"));
  }

  public TokenPair oauthCallback(
      String provider, String authorizationCode, String deviceInfoJson, String ipAddress) {
    OAuthProvider oauthProvider = resolveProvider(provider);
    String email;
    try {
      email = oauthProvider.exchangeCodeForVerifiedEmail(authorizationCode);
    } catch (Exception e) {
      throw new OAuthLoginRejectedException("OAuth exchange failed", e);
    }
    User user =
        userRepository
            .findByEmail(email)
            .orElseThrow(() -> new OAuthLoginRejectedException("No account exists for this email"));
    return issueNewSession(user, deviceInfoJson, ipAddress);
  }

  private OAuthProvider resolveProvider(String provider) {
    return switch (provider.toLowerCase(java.util.Locale.ROOT)) {
      case "google" -> googleOAuthProvider;
      case "apple" -> appleOAuthProvider;
      default -> throw new OAuthLoginRejectedException("Unsupported OAuth provider: " + provider);
    };
  }

  /**
   * TICKET-118 — {@code accept-invite}'s own "log the user in" step (a brand-new or a
   * pre-existing user, either way accept-invite issues a real session exactly like {@link #login}
   * does, just without a password check — the token itself is the credential). Public wrapper
   * around {@link #issueNewSession} so {@code AuthSessionApiImpl} can call it without duplicating
   * the suspended-account guard/refresh-token/JWT-issuance logic.
   */
  public TokenPair issueSessionForExistingUser(UUID userId, String deviceInfoJson, String ipAddress) {
    User user = userRepository.findById(userId).orElseThrow(NoSuchElementException::new);
    return issueNewSession(user, deviceInfoJson, ipAddress);
  }

  /**
   * TICKET-117 — the single choke point {@link #login} and {@link #refresh} both funnel through, so
   * a suspended/deleted account is blocked from both paths in one place rather than duplicating the
   * check. Checked before any session/refresh-token row is written, not after — a rejected caller
   * shouldn't leave an orphan session row behind.
   */
  private TokenPair issueNewSession(User user, String deviceInfoJson, String ipAddress) {
    if (!"ACTIVE".equals(user.status())) {
      throw new AccountSuspendedException();
    }
    String refreshToken = generateOpaqueToken();
    String hash = hashToken(refreshToken);
    Instant expiresAt = Instant.now().plus(REFRESH_TOKEN_TTL_DAYS, ChronoUnit.DAYS);
    sessionRepository.create(user.id(), hash, deviceInfoJson, ipAddress, expiresAt);
    List<String> roles = userRepository.findRoleNames(user.id());
    String accessToken =
        jwtService.issueAccessToken(user.id(), user.email(), roles, user.twoFactorEnabled());
    return new TokenPair(accessToken, refreshToken, JwtService.ACCESS_TOKEN_TTL_SECONDS);
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

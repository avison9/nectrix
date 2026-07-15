package com.nectrix.coreapp.auth.service;

import com.nectrix.coreapp.auth.config.AuthProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Issues our own HS256-signed access tokens. Verification of incoming bearer tokens on protected
 * routes is Spring Security's job (see SecurityConfig's {@code JwtDecoder} bean, sharing the exact
 * same secret) — this class only ever signs, never parses.
 */
@Service
public class JwtService {

  /** docs/07-auth-onboarding-broker-linking.md §7.1 — "short-lived JWT access token (~15 min)". */
  public static final long ACCESS_TOKEN_TTL_SECONDS = 15 * 60;

  private static final String ISSUER = "nectrix-core-app";

  private final MACSigner signer;

  public JwtService(AuthProperties props) {
    byte[] secretBytes = Base64.getDecoder().decode(props.jwt().secret());
    if (secretBytes.length < 32) {
      throw new IllegalStateException(
          "JWT_SIGNING_SECRET must decode to at least 256 bits (32 bytes)");
    }
    try {
      this.signer = new MACSigner(secretBytes);
    } catch (JOSEException e) {
      throw new IllegalStateException("Failed to initialize JWT signer", e);
    }
  }

  /**
   * roles is embedded as a claim (not fetched per-request) so TICKET-006's role checks don't need a
   * DB round trip. TICKET-110 — two_factor_enabled is embedded the same way, so the mandatory-2FA-
   * before-trade-capable-linking gate (docs/17-security-architecture.md §17.3) doesn't need one
   * either; it's a snapshot as of login/token-issue time, same staleness window every other claim
   * here already accepts (a user enabling 2FA mid-session must simply get a fresh token, same as a
   * role change would require).
   */
  public String issueAccessToken(
      UUID userId, String email, List<String> roles, boolean twoFactorEnabled) {
    return sign(
        new JWTClaimsSet.Builder()
            .subject(userId.toString())
            .issuer(ISSUER)
            .claim("email", email)
            .claim("roles", roles)
            .claim("two_factor_enabled", twoFactorEnabled));
  }

  /**
   * TICKET-006 — an admin/support-initiated impersonation session: a normal access token for {@code
   * targetUserId} (same claims, same 15-min TTL as a real login — an impersonation session
   * shouldn't outlive one), plus an {@code impersonated_by} claim naming the acting admin/support
   * user. Downstream consumers (audit logging, future authorization checks) can always tell an
   * impersonated request apart from a genuine self-service one by checking for this claim's
   * presence — see docs/12-analytics-notifications-admin.md §12.3's "every action taken while
   * impersonating is tagged... with both the admin's and the impersonated user's IDs".
   */
  public String issueImpersonationToken(
      UUID targetUserId,
      String targetEmail,
      List<String> targetRoles,
      boolean targetTwoFactorEnabled,
      UUID actingAdminId) {
    return sign(
        new JWTClaimsSet.Builder()
            .subject(targetUserId.toString())
            .issuer(ISSUER)
            .claim("email", targetEmail)
            .claim("roles", targetRoles)
            .claim("two_factor_enabled", targetTwoFactorEnabled)
            .claim("impersonated_by", actingAdminId.toString()));
  }

  private String sign(JWTClaimsSet.Builder claimsBuilder) {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        claimsBuilder
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(ACCESS_TOKEN_TTL_SECONDS, ChronoUnit.SECONDS)))
            .jwtID(UUID.randomUUID().toString())
            .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    try {
      jwt.sign(signer);
    } catch (JOSEException e) {
      throw new IllegalStateException("Failed to sign access token", e);
    }
    return jwt.serialize();
  }
}

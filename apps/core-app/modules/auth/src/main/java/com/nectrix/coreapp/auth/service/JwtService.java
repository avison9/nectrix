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
   * DB round trip.
   */
  public String issueAccessToken(UUID userId, String email, List<String> roles) {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject(userId.toString())
            .issuer(ISSUER)
            .claim("email", email)
            .claim("roles", roles)
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

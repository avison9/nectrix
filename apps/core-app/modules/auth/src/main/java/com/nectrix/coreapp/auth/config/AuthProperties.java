package com.nectrix.coreapp.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Registered via {@code @EnableConfigurationProperties(AuthProperties.class)} on {@link
 * SecurityConfig} (Boot's documented preference over {@code @Component + @ConfigurationProperties})
 * — see application.yml's {@code nectrix.auth.*} block for the backing values.
 */
@ConfigurationProperties(prefix = "nectrix.auth")
public record AuthProperties(Jwt jwt, TwoFactor twoFactor, RateLimit rateLimit, OAuth oauth) {

  /**
   * HS256 signing secret, base64-encoded, >=256 bits — shared by JwtService (issue) and
   * SecurityConfig's JwtDecoder (verify).
   */
  public record Jwt(String secret) {}

  /**
   * encryptionKey backs the TEMPORARY stub AES-GCM implementation of {@link
   * com.nectrix.coreapp.auth.service.TwoFactorSecretCipher} — replaced wholesale once TICKET-011's
   * real KMS-backed envelope encryption exists. issuer is the TOTP QR code's label (e.g.
   * "Nectrix").
   */
  public record TwoFactor(String encryptionKey, String issuer) {}

  /**
   * Applies to /auth/login and /auth/2fa/verify specifically — independent of any general API rate
   * limiting.
   */
  public record RateLimit(int maxAttempts, long windowSeconds) {}

  public record OAuth(Provider google, Provider apple) {}

  public record Provider(String clientId, String clientSecret, String redirectUri) {}
}

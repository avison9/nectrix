package com.nectrix.coreapp.auth.service;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;

/** docs/07-auth-onboarding-broker-linking.md §7.1 — "Email + password (Argon2id hashing)". */
@Service
public class PasswordService {

  // OWASP-recommended Argon2id baseline (2024 cheat sheet): m=19 MiB, t=2,
  // p=1, 16-byte salt, 32-byte hash. Affordable per-attempt cost is fine since
  // login is separately rate-limited (RateLimiterService) — this isn't the
  // only defense against brute-forcing.
  private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);

  public String hash(String rawPassword) {
    return encoder.encode(rawPassword);
  }

  public boolean matches(String rawPassword, String hash) {
    return encoder.matches(rawPassword, hash);
  }
}

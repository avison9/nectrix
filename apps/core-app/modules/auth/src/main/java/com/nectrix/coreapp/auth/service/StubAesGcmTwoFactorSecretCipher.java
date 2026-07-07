package com.nectrix.coreapp.auth.service;

import com.nectrix.coreapp.auth.config.AuthProperties;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * TEMPORARY stand-in for TICKET-011's real KMS-backed envelope encryption — a single local AES-GCM
 * key from an env var, no key-version tracking, no KMS. Must be swapped out before any production
 * credential flows through this (i.e. before Phase 1 broker linking), per TICKET-005's own scope
 * note. Stored format: base64(12-byte nonce || ciphertext+tag) — self-contained, no separate
 * key-version column needed for this stub (there's only ever one key).
 */
@Service
public class StubAesGcmTwoFactorSecretCipher implements TwoFactorSecretCipher {

  private static final String ALGORITHM = "AES/GCM/NoPadding";
  private static final int GCM_TAG_LENGTH_BITS = 128;
  private static final int NONCE_LENGTH_BYTES = 12;

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  public StubAesGcmTwoFactorSecretCipher(AuthProperties props) {
    byte[] keyBytes = Base64.getDecoder().decode(props.twoFactor().encryptionKey());
    if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
      throw new IllegalStateException(
          "TWO_FACTOR_SECRET_ENCRYPTION_KEY must decode to 16, 24, or 32 bytes (AES-128/192/256)");
    }
    this.key = new SecretKeySpec(keyBytes, "AES");
  }

  @Override
  public String encrypt(String plaintextSecret) {
    try {
      byte[] nonce = new byte[NONCE_LENGTH_BYTES];
      random.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance(ALGORITHM);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
      byte[] ciphertext =
          cipher.doFinal(plaintextSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      byte[] combined = new byte[nonce.length + ciphertext.length];
      System.arraycopy(nonce, 0, combined, 0, nonce.length);
      System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
      return Base64.getEncoder().encodeToString(combined);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to encrypt 2FA secret", e);
    }
  }

  @Override
  public String decrypt(String ciphertext) {
    try {
      byte[] combined = Base64.getDecoder().decode(ciphertext);
      byte[] nonce = new byte[NONCE_LENGTH_BYTES];
      byte[] encrypted = new byte[combined.length - NONCE_LENGTH_BYTES];
      System.arraycopy(combined, 0, nonce, 0, NONCE_LENGTH_BYTES);
      System.arraycopy(combined, NONCE_LENGTH_BYTES, encrypted, 0, encrypted.length);
      Cipher cipher = Cipher.getInstance(ALGORITHM);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
      byte[] plaintext = cipher.doFinal(encrypted);
      return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to decrypt 2FA secret", e);
    }
  }
}

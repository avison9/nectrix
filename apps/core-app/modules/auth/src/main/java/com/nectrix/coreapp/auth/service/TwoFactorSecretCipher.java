package com.nectrix.coreapp.auth.service;

/**
 * Encrypts/decrypts {@code users.two_factor_secret} before it ever touches the database. TICKET-005
 * explicitly allows a stub here (TICKET-011's real envelope-encryption utility — {@code
 * encryptField(plaintext) -> {ciphertext, keyVersion}} / {@code decryptField(ciphertext,
 * keyVersion) -> plaintext} — doesn't exist yet). {@link StubAesGcmTwoFactorSecretCipher} is that
 * stub; this interface exists so swapping in the real KMS-backed implementation later touches
 * nothing but this one class's wiring, not any caller.
 */
public interface TwoFactorSecretCipher {

  String encrypt(String plaintextSecret);

  String decrypt(String ciphertext);
}

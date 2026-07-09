package com.nectrix.coreapp.crypto.api;

/**
 * TICKET-011's KMS-backed envelope-encryption utility (docs/17-security-architecture.md §17.2,
 * docs/07-auth-onboarding-broker-linking.md §7.8): a per-record Data Encryption Key (DEK) wrapped
 * by a cloud-KMS-held Key Encryption Key (KEK). Protects {@code users.two_factor_secret} today;
 * {@code broker_accounts.credentials_ciphertext} will depend on this same interface once Phase 1
 * broker linking exists (out of scope here — this ticket delivers the reusable utility only).
 *
 * <p>Callers must persist both {@link EncryptedField#ciphertext()} and {@link
 * EncryptedField#keyVersion()} — the ciphertext alone is not decryptable without knowing which key
 * version wrapped its DEK.
 */
public interface EnvelopeEncryptionService {

  EncryptedField encryptField(String plaintext);

  String decryptField(String ciphertext, short keyVersion);
}

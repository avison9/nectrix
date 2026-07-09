package com.nectrix.coreapp.crypto.kms;

/**
 * Thin abstraction over a cloud KMS's envelope-encryption primitives — generate/decrypt a
 * per-record Data Encryption Key (DEK), wrapped by a Key Encryption Key (KEK) the KMS itself never
 * releases in plaintext. Named distinctly from AWS SDK v2's own {@code
 * software.amazon.awssdk.services.kms.KmsClient} to avoid a confusing import collision in {@link
 * AwsEnvelopeKmsClient}, which wraps that exact class.
 */
public interface EnvelopeKmsClient {

  DataKey generateDataKey(String kmsKeyId);

  byte[] decryptDataKey(String kmsKeyId, byte[] encryptedDek);

  /** plaintextKey must be zeroed by the caller as soon as it's done being used. */
  record DataKey(byte[] plaintextKey, byte[] encryptedKey) {}
}

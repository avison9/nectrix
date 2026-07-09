package com.nectrix.coreapp.crypto.api;

/**
 * The result of {@link EnvelopeEncryptionService#encryptField} — both parts must be stored, the
 * ciphertext is not decryptable without knowing which key version wrapped its DEK.
 */
public record EncryptedField(String ciphertext, short keyVersion) {}

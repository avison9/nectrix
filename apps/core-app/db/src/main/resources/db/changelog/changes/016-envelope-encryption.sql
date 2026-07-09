--liquibase formatted sql

-- TICKET-011 (Secrets Management & Envelope Encryption Utility) —
-- docs/17-security-architecture.md §17.2 / docs/07-auth-onboarding-broker-linking.md
-- §7.8: the application-level key-version registry that supports KEK rotation
-- "without a synchronous full-table re-encryption." Deliberately explicit
-- state, not just AWS/GCP KMS's own opaque internal key rotation — a
-- "rotation" here is inserting a new current row (apps/core-app/modules/crypto's
-- KeyVersionRepository#rotate), which is what makes old ciphertext (tagged
-- with the version that was current when it was written) stay decryptable
-- after the current version moves on.
--changeset nectrix:016-kms-key-versions
CREATE TABLE kms_key_versions (
    version     SMALLINT PRIMARY KEY,
    kms_key_id  TEXT NOT NULL,
    is_current  BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Exactly one current version at a time — a partial unique index (not just
-- an application-level invariant) so a bug in KeyVersionRepository#rotate
-- can never silently leave two "current" rows for #current() to pick
-- unpredictably between.
CREATE UNIQUE INDEX idx_kms_key_versions_current ON kms_key_versions(is_current) WHERE is_current;
--rollback DROP TABLE IF EXISTS kms_key_versions CASCADE;

-- users.two_factor_secret is now encrypted via EnvelopeEncryptionService
-- (apps/core-app/modules/crypto), replacing TICKET-005's temporary
-- StubAesGcmTwoFactorSecretCipher — nullable, same as two_factor_secret
-- itself (no 2FA enrolled yet = both columns null).
--changeset nectrix:016-users-two-factor-secret-key-version
ALTER TABLE users ADD COLUMN two_factor_secret_key_version SMALLINT REFERENCES kms_key_versions(version);
--rollback ALTER TABLE users DROP COLUMN IF EXISTS two_factor_secret_key_version;

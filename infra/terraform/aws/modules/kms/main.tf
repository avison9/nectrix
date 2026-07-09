# TICKET-011 — the KEK for the application-level envelope-encryption utility
# (apps/core-app/modules/crypto) that protects users.two_factor_secret today
# and broker_accounts.credentials_ciphertext in Phase 1
# (docs/17-security-architecture.md §17.2, docs/07-auth-onboarding-broker-linking.md
# §7.8). This is deliberately a *separate* concern from the CMK-vs-default-
# encryption question the other 11 checkov-skip items track (RDS/ElastiCache/
# S3/... at-rest encryption) — this key exists to wrap per-record DEKs at the
# application layer, not to encrypt an engine's own storage volumes. Wiring
# those other engines onto a CMK (possibly this same key, possibly dedicated
# ones) is separate, future-scoped work once a real cloud provider is chosen.
#
# enable_key_rotation is AWS KMS's own opaque annual rotation of the key
# material under one stable key ID — a defense-in-depth floor, not what
# actually satisfies AC2's "rotate to version 2, old ciphertext still
# decrypts" requirement. That's the application's own explicit
# kms_key_versions registry (apps/core-app/db's 016-envelope-encryption.sql):
# a "rotation" there means minting a *new* aws_kms_key (a new alias, e.g.
# alias/nectrix-<env>-envelope-v2) and recording it as the current version,
# while the old key/alias keeps existing so version-1 ciphertext stays
# decryptable. This module provisions "version 1" only; provisioning v2+ is
# a copy of this module invoked again at actual rotation time, not something
# to pre-provision speculatively now.
resource "aws_kms_key" "envelope_v1" {
  description             = "${var.name_prefix} envelope-encryption KEK, version 1 (apps/core-app/modules/crypto)"
  deletion_window_in_days = 30
  enable_key_rotation     = true
}

resource "aws_kms_alias" "envelope_v1" {
  name          = "alias/${var.name_prefix}-envelope-v1"
  target_key_id = aws_kms_key.envelope_v1.key_id
}

# Scoped to exactly this key (not "*") — the app never needs KMS access
# beyond its own envelope-encryption KEK(s).
resource "aws_iam_policy" "access" {
  name        = "${var.name_prefix}-envelope-encryption-kms-access"
  description = "Encrypt/Decrypt/GenerateDataKey access to the envelope-encryption KEK, for the core-app pod's IRSA role"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "kms:Encrypt",
        "kms:Decrypt",
        "kms:GenerateDataKey",
        "kms:DescribeKey",
      ]
      Resource = [aws_kms_key.envelope_v1.arn]
    }]
  })
}

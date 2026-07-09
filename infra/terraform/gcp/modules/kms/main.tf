# TICKET-011 — the GCP-side KEK for the application-level envelope-encryption
# utility (apps/core-app/modules/crypto). See ../../aws/modules/kms's own
# comment for the full rationale — same design, GCP resources. rotation_period
# is Cloud KMS's own opaque automatic rotation (a defense-in-depth floor);
# AC2's "rotate to version 2, old ciphertext still decrypts" is the
# application's own explicit kms_key_versions registry, satisfied by minting
# a *new* google_kms_crypto_key (this module invoked again) at actual
# rotation time, not by this field alone.
resource "google_kms_key_ring" "envelope" {
  name     = "${var.name_prefix}-envelope-encryption"
  location = var.region
}

resource "google_kms_crypto_key" "envelope_v1" {
  name            = "${var.name_prefix}-envelope-v1"
  key_ring        = google_kms_key_ring.envelope.id
  purpose         = "ENCRYPT_DECRYPT"
  rotation_period = "7776000s" # 90 days

  lifecycle {
    prevent_destroy = true
  }
}

# TICKET-011 — GCP-side counterpart to ../../aws/modules/secrets-manager. See
# that module's comment for the full rationale (service-to-service creds,
# distinct from ../kms's application-level envelope encryption).
resource "google_secret_manager_secret" "example" {
  secret_id = "${var.name_prefix}-service-credentials-example"

  replication {
    auto {}
  }
}

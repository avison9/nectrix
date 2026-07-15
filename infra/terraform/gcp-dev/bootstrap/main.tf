# One-off bootstrap: creates the GCS bucket that ../backend.tf's commented
# `backend "gcs"` block points at. Same standalone, chicken-and-egg-solving
# pattern as infra/terraform/gcp/bootstrap — its own local state, never
# migrated, applied exactly once by hand:
#
#   cd infra/terraform/gcp-dev/bootstrap
#   terraform init
#   terraform apply
#
# Not wired into any CI/CD path.
resource "google_storage_bucket" "state" {
  name     = var.state_bucket_name
  location = var.region

  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"

  versioning {
    enabled = true
  }

  lifecycle {
    prevent_destroy = true
  }
}

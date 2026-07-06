# One-off bootstrap: creates the GCS bucket that infra/terraform/gcp/backend.tf's
# commented `backend "gcs"` block points at.
#
# This has its own local state (never migrated to the backend it creates — the
# same chicken-and-egg reason it exists standalone) and is meant to be applied
# exactly once, by hand, against a real project:
#
#   cd infra/terraform/gcp/bootstrap
#   terraform init
#   terraform apply
#
# Not wired into any CI/CD path, and out of scope for this ticket's offline
# verification (see infra/terraform/README.md). GCS has native object locking
# via versioning + retention policy — no separate lock-table resource needed
# (unlike AWS's DynamoDB table).

terraform {
  required_version = ">= 1.7.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.10"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

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

# Replaces MinIO (local-dev-only, see deploy/components/local-minio) for real
# environments — same S3-compatible API/SDK path the application layer already
# targets (GCS supports the S3 API via its interoperability mode), so this is
# purely a credentials/endpoint change per docs/13-technology-stack.md §13.2.

resource "google_storage_bucket" "this" {
  name     = var.bucket_name
  location = var.location

  uniform_bucket_level_access = true

  versioning {
    enabled = true
  }

  public_access_prevention = "enforced"
}

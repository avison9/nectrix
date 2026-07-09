variable "name_prefix" {
  type = string
}

variable "workload_identity_pool" {
  description = "The cluster's workload_identity_config.workload_pool, e.g. <project>.svc.id.goog"
  type        = string
}

variable "gcs_bucket_name" {
  description = "GCS bucket name this GSA should get object admin access to"
  type        = string
}

variable "kms_crypto_key_id" {
  description = "Envelope-encryption KEK ID this GSA should get encrypt/decrypt access to (infra/terraform/gcp/modules/kms output)"
  type        = string
}

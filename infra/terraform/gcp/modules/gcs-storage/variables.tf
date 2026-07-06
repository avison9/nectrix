variable "bucket_name" {
  description = "Globally-unique GCS bucket name for this environment's object storage"
  type        = string
}

variable "location" {
  description = "GCS bucket location (region or multi-region, e.g. US or us-east1)"
  type        = string
}

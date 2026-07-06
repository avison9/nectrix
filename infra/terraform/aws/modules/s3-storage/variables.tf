variable "bucket_name" {
  description = "Globally-unique S3 bucket name for this environment's object storage (statements, invoices, KYC docs, fee reports — see docs/06-database-schema.md)"
  type        = string
}

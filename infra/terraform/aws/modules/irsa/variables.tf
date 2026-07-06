variable "name_prefix" {
  type = string
}

variable "oidc_provider_arn" {
  type = string
}

variable "oidc_provider_url" {
  description = "OIDC provider URL, without the https:// scheme (as required by the IAM trust policy Condition key)"
  type        = string
}

variable "s3_access_policy_arn" {
  description = "IAM policy ARN granting access to this environment's S3 bucket (infra/terraform/aws/modules/s3-storage output)"
  type        = string
}

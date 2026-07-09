variable "name_prefix" {
  type = string
}

variable "kms_key_arn" {
  description = "KMS key ARN to encrypt this secret with (infra/terraform/aws/modules/kms output)"
  type        = string
}

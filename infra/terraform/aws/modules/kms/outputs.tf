output "key_id" {
  value = aws_kms_key.envelope_v1.key_id
}

output "key_arn" {
  value = aws_kms_key.envelope_v1.arn
}

output "alias_name" {
  value = aws_kms_alias.envelope_v1.name
}

output "access_policy_arn" {
  value = aws_iam_policy.access.arn
}

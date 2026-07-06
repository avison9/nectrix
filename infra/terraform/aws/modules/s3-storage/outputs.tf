output "bucket_name" {
  value = aws_s3_bucket.this.bucket
}

output "bucket_arn" {
  value = aws_s3_bucket.this.arn
}

output "access_policy_arn" {
  value = aws_iam_policy.access.arn
}

output "repository_urls" {
  description = "Map of app name to full ECR repository URL, e.g. for `kustomize edit set image`"
  value       = { for k, r in aws_ecr_repository.this : k => r.repository_url }
}

output "repository_arns" {
  value = { for k, r in aws_ecr_repository.this : k => r.arn }
}

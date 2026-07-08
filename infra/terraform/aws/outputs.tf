output "cluster_name" {
  value = module.eks.cluster_name
}

output "cluster_endpoint" {
  value = module.eks.cluster_endpoint
}

output "cluster_security_group_id" {
  value = module.eks.cluster_security_group_id
}

output "oidc_provider_arn" {
  value = module.eks.oidc_provider_arn
}

output "irsa_role_arns" {
  value = module.irsa.role_arns
}

output "postgres_endpoint" {
  value = module.rds_postgres.endpoint
}

output "postgres_master_username" {
  value = module.rds_postgres.master_username
}

output "postgres_master_password" {
  value     = module.rds_postgres.master_password
  sensitive = true
}

output "redis_configuration_endpoint" {
  value = module.elasticache_redis.configuration_endpoint
}

output "s3_bucket_name" {
  value = module.s3_storage.bucket_name
}

output "waf_web_acl_arn" {
  value = module.alb_waf.web_acl_arn
}

output "ecr_repository_urls" {
  description = "Map of app name to full ECR repository URL — wire into kustomize edit set image / GitHub repo variables"
  value       = module.ecr.repository_urls
}

output "ci_ecr_push_role_arn" {
  description = "Set as the AWS_ECR_PUSH_ROLE_ARN repo variable once this is actually applied (see .github/workflows/main-pipeline.yml)"
  value       = module.github_oidc.ci_ecr_push_role_arn
}

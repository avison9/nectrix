output "cluster_name" {
  value = module.gke.cluster_name
}

output "cluster_endpoint" {
  value = module.gke.cluster_endpoint
}

output "workload_identity_pool" {
  value = module.gke.workload_identity_pool
}

output "app_storage_gsa_email" {
  value = module.workload_identity.gsa_email
}

output "postgres_private_ip" {
  value = module.cloudsql_postgres.private_ip_address
}

output "postgres_db_user" {
  value = module.cloudsql_postgres.db_user
}

output "postgres_db_password" {
  value     = module.cloudsql_postgres.db_password
  sensitive = true
}

output "redis_discovery_endpoints" {
  value = module.memorystore_redis_cluster.discovery_endpoints
}

output "gcs_bucket_name" {
  value = module.gcs_storage.bucket_name
}

output "cloud_armor_policy_name" {
  value = module.glb_cloud_armor.policy_name
}

output "artifact_registry_url" {
  description = "Base path — images are <artifact_registry_url>/<app>:<tag>. Wire into kustomize edit set image."
  value       = module.artifact_registry.repository_url
}

output "ci_artifact_registry_push_sa" {
  value = module.artifact_registry.ci_push_service_account_email
}

output "ci_workload_identity_provider" {
  description = "Set as the GCP_WORKLOAD_IDENTITY_PROVIDER repo variable once this is actually applied (see .github/workflows/main-pipeline.yml)"
  value       = module.artifact_registry.workload_identity_provider
}

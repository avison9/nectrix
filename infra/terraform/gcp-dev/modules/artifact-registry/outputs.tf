output "repository_url" {
  description = "Base path — images are <repository_url>/<app>:<tag>. Set as the GCP_ARTIFACT_REGISTRY_URL repo variable."
  value       = "${google_artifact_registry_repository.this.location}-docker.pkg.dev/${google_artifact_registry_repository.this.project}/${google_artifact_registry_repository.this.repository_id}"
}

output "repository_id" {
  value = google_artifact_registry_repository.this.repository_id
}

output "location" {
  value = google_artifact_registry_repository.this.location
}

output "ci_push_service_account_email" {
  description = "Set as the GCP_CI_PUSH_SERVICE_ACCOUNT repo variable."
  value       = google_service_account.ci_push.email
}

output "ci_deploy_service_account_email" {
  description = "Set as the GCP_CI_DEPLOY_SERVICE_ACCOUNT repo variable — used by deploy-dev to open the IAP tunnel and run kubectl remotely."
  value       = google_service_account.ci_deploy.email
}

output "workload_identity_provider" {
  description = "Full provider resource name, for google-github-actions/auth's workload_identity_provider input. Set as GCP_WORKLOAD_IDENTITY_PROVIDER."
  value       = google_iam_workload_identity_pool_provider.github_actions.name
}

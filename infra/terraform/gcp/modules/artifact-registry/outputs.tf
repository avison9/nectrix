output "repository_url" {
  description = "Base path — images are <repository_url>/<app>:<tag>, e.g. for `kustomize edit set image`"
  value       = "${google_artifact_registry_repository.this.location}-docker.pkg.dev/${google_artifact_registry_repository.this.project}/${google_artifact_registry_repository.this.repository_id}"
}

output "ci_push_service_account_email" {
  value = google_service_account.ci_push.email
}

output "workload_identity_provider" {
  description = "Full provider resource name, for google-github-actions/auth's workload_identity_provider input"
  value       = google_iam_workload_identity_pool_provider.github_actions.name
}

# One Docker-format repository holds every app image, distinguished by image
# path — same shape as infra/terraform/gcp/modules/artifact-registry, kept
# separate (not reused directly) since this repo/project is dev-only and its
# own WIF trust + cleanup policy are tuned for a fast-moving dev environment
# rather than staging/production.
resource "google_artifact_registry_repository" "this" {
  location      = var.region
  repository_id = "nectrix-dev"
  format        = "DOCKER"
  description   = "Nectrix dev-environment images — pulled by the nectrix-dev VM's k3s node"

  cleanup_policies {
    id     = "expire-old-images"
    action = "DELETE"
    condition {
      older_than = "259200s" # 3 days — dev churns faster than staging/production's 7-day window
      tag_state  = "UNTAGGED"
    }
  }

  cleanup_policies {
    id     = "keep-recent"
    action = "KEEP"
    most_recent_versions {
      keep_count = 10
    }
  }
}

# Workload Identity Federation — GitHub Actions authenticates with a
# short-lived federated token, no long-lived GCP service-account key stored
# in GitHub Secrets. Same mechanism infra/terraform/gcp/modules/artifact-registry
# documents; this pool is dedicated to gcp-dev so its trust condition can stay
# scoped to exactly this deploy path.
resource "google_iam_workload_identity_pool" "github_actions" {
  workload_identity_pool_id = "${var.name_prefix}-github-actions"
  display_name              = "GitHub Actions (nectrix-dev)"
}

resource "google_iam_workload_identity_pool_provider" "github_actions" {
  workload_identity_pool_id          = google_iam_workload_identity_pool.github_actions.workload_identity_pool_id
  workload_identity_pool_provider_id = "github-actions"
  display_name                       = "GitHub Actions OIDC"

  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.repository" = "assertion.repository"
    "attribute.ref"        = "assertion.ref"
  }

  # Scoped to pushes from main-pipeline.yml (on: push to main) only — matches
  # where both the AR push step and the deploy-dev job live.
  attribute_condition = "assertion.repository == '${var.github_repo}' && assertion.ref == 'refs/heads/main'"

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

# --- CI image push identity ---
resource "google_service_account" "ci_push" {
  account_id   = "${var.name_prefix}-ci-ar-push"
  display_name = "CI push access to Artifact Registry (nectrix-dev, via WIF)"
}

resource "google_artifact_registry_repository_iam_member" "ci_push" {
  location   = google_artifact_registry_repository.this.location
  repository = google_artifact_registry_repository.this.repository_id
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.ci_push.email}"
}

resource "google_service_account_iam_member" "ci_push_wif_binding" {
  service_account_id = google_service_account.ci_push.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github_actions.name}/attribute.repository/${var.github_repo}"
}

# --- CI deploy identity — opens the IAP tunnel to the VM and runs kubectl
# remotely (see .github/workflows/main-pipeline.yml's deploy-dev job).
# Project-level roles, not repository-level, since IAP tunneling and
# instance lookup aren't Artifact-Registry concerns.
resource "google_service_account" "ci_deploy" {
  account_id   = "${var.name_prefix}-ci-deploy"
  display_name = "CI deploy access to the nectrix-dev VM (IAP tunnel + kubectl, via WIF)"
}

resource "google_project_iam_member" "ci_deploy_iap_tunnel" {
  project = var.project_id
  role    = "roles/iap.tunnelResourceAccessor"
  member  = "serviceAccount:${google_service_account.ci_deploy.email}"
}

resource "google_project_iam_member" "ci_deploy_compute_viewer" {
  project = var.project_id
  role    = "roles/compute.viewer"
  member  = "serviceAccount:${google_service_account.ci_deploy.email}"
}

# OS Login with sudo — lets gcloud compute scp/ssh --tunnel-through-iap
# authenticate via IAM instead of injecting a temporary SSH key into instance
# metadata (which needs compute.instances.setMetadata, not granted here on
# purpose — see modules/vm's enable-oslogin metadata note). *Admin* login
# specifically (not the plain osLogin role) since deploy-dev's steps run
# `sudo -E kubectl`/`docker compose` on the box.
resource "google_project_iam_member" "ci_deploy_os_admin_login" {
  project = var.project_id
  role    = "roles/compute.osAdminLogin"
  member  = "serviceAccount:${google_service_account.ci_deploy.email}"
}

resource "google_service_account_iam_member" "ci_deploy_wif_binding" {
  service_account_id = google_service_account.ci_deploy.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github_actions.name}/attribute.repository/${var.github_repo}"
}

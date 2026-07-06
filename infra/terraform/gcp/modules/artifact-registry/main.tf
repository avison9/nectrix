# One Docker-format repository holds all 4 deployables, distinguished by image
# path (<region>-docker.pkg.dev/<project>/nectrix/<app>:<tag>) — unlike ECR,
# Artifact Registry repositories aren't 1:1 with image names.
resource "google_artifact_registry_repository" "this" {
  location      = var.region
  repository_id = "nectrix"
  format        = "DOCKER"
  description   = "Nectrix backend deployable images (core-app, copy-engine, broker-adapters, mt5-bridge-gateway)"

  cleanup_policies {
    id     = "expire-old-images"
    action = "DELETE"
    condition {
      older_than = "604800s" # 7 days, matches ../ecr's untagged_expiry_days default
      tag_state  = "UNTAGGED"
    }
  }

  cleanup_policies {
    id     = "keep-recent"
    action = "KEEP"
    most_recent_versions {
      keep_count = 30 # matches ../ecr's max_tagged_images default
    }
  }
}

resource "google_artifact_registry_repository_iam_member" "node_reader" {
  location   = google_artifact_registry_repository.this.location
  repository = google_artifact_registry_repository.this.repository_id
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${var.gke_node_service_account_email}"
}

# Workload Identity Federation — lets GitHub Actions push with a short-lived
# federated token, no long-lived GCP service-account key stored in GitHub
# Secrets. Analogous to ../../aws/modules/github-oidc, GCP's equivalent
# mechanism.
resource "google_iam_workload_identity_pool" "github_actions" {
  workload_identity_pool_id = "${var.name_prefix}-github-actions"
  display_name              = "GitHub Actions"
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
  # where the Artifact Registry push step actually lives (see
  # .github/workflows/main-pipeline.yml).
  attribute_condition = "assertion.repository == '${var.github_repo}' && assertion.ref == 'refs/heads/main'"

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

resource "google_service_account" "ci_push" {
  account_id   = "${var.name_prefix}-ci-ar-push"
  display_name = "CI push access to Artifact Registry (via Workload Identity Federation)"
}

resource "google_artifact_registry_repository_iam_member" "ci_push" {
  location   = google_artifact_registry_repository.this.location
  repository = google_artifact_registry_repository.this.repository_id
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.ci_push.email}"
}

resource "google_service_account_iam_member" "wif_binding" {
  service_account_id = google_service_account.ci_push.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github_actions.name}/attribute.repository/${var.github_repo}"
}

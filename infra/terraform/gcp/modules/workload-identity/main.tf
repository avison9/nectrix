# GCP's analogue to AWS's IRSA (../../aws/modules/irsa) — cluster-autoscaler
# needs no equivalent here at all, since GKE's node-pool autoscaling{} block is
# built into the control plane (see ../gke). This module only wires up the
# app-facing GSA <-> KSA binding for GCS access.

resource "google_service_account" "app_storage_access" {
  account_id   = "${var.name_prefix}-app-storage"
  display_name = "Nectrix ${var.name_prefix} core-app storage access"
}

resource "google_storage_bucket_iam_member" "app_storage_access" {
  bucket = var.gcs_bucket_name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.app_storage_access.email}"
}

# Binds the GSA to the core-app namespace's "core-app" KubernetesServiceAccount
# via Workload Identity — annotate that KSA with
# iam.gke.io/gcp-service-account: <output.gsa_email> to complete the wiring.
resource "google_service_account_iam_member" "workload_identity_binding" {
  service_account_id = google_service_account.app_storage_access.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.workload_identity_pool}[core-app/core-app]"
}

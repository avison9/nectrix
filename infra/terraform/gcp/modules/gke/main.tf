resource "google_container_cluster" "this" {
  name     = "${var.name_prefix}-cluster"
  location = var.region

  network    = var.network_self_link
  subnetwork = var.subnetwork_self_link

  # Node pool managed separately below (google_container_node_pool) — the
  # cluster's own default pool is removed immediately after creation.
  remove_default_node_pool = true
  initial_node_count       = 1

  networking_mode = "VPC_NATIVE"
  ip_allocation_policy {
    cluster_secondary_range_name  = var.pods_range_name
    services_secondary_range_name = var.services_range_name
  }

  private_cluster_config {
    enable_private_nodes    = true
    enable_private_endpoint = false
    master_ipv4_cidr_block  = "172.16.0.0/28"
  }

  # Placeholder — narrow to the real office/VPN/bastion CIDR before a real
  # apply (same "tighten before real apply" caveat as
  # infra/terraform/aws/modules/irsa's condensed IAM policies).
  master_authorized_networks_config {
    cidr_blocks {
      cidr_block   = "0.0.0.0/0"
      display_name = "placeholder-restrict-before-real-apply"
    }
  }

  master_auth {
    client_certificate_config {
      issue_client_certificate = false
    }
  }

  workload_identity_config {
    workload_pool = "${data.google_project.this.project_id}.svc.id.goog"
  }

  release_channel {
    channel = "REGULAR"
  }

  enable_intranode_visibility = true
  enable_shielded_nodes       = true

  network_policy {
    enabled = true
  }

  resource_labels = {
    project     = "nectrix"
    environment = replace(var.name_prefix, "nectrix-", "")
  }

  deletion_protection = true
}

data "google_project" "this" {}

# Dedicated node service account — narrower than the project's default Compute
# Engine SA, which every other unrelated resource in the project would
# otherwise implicitly trust. Granted only what nodes actually need: write
# logs/metrics, pull images (the artifactregistry.reader binding lives in
# ../artifact-registry, which takes this SA's email as an input to avoid a
# circular module dependency).
resource "google_service_account" "node" {
  account_id   = "${var.name_prefix}-gke-node"
  display_name = "Nectrix ${var.name_prefix} GKE node pool"
}

resource "google_project_iam_member" "node_logging" {
  project = data.google_project.this.project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${google_service_account.node.email}"
}

resource "google_project_iam_member" "node_monitoring_writer" {
  project = data.google_project.this.project_id
  role    = "roles/monitoring.metricWriter"
  member  = "serviceAccount:${google_service_account.node.email}"
}

resource "google_project_iam_member" "node_monitoring_viewer" {
  project = data.google_project.this.project_id
  role    = "roles/monitoring.viewer"
  member  = "serviceAccount:${google_service_account.node.email}"
}

resource "google_container_node_pool" "default" {
  name     = "${var.name_prefix}-default"
  location = var.region
  cluster  = google_container_cluster.this.name

  node_count = var.node_initial_count

  # This IS the cluster autoscaler for GKE — no separate controller Deployment
  # or IAM role needed, unlike EKS (compare infra/terraform/aws/modules/irsa).
  autoscaling {
    min_node_count = var.node_min_count
    max_node_count = var.node_max_count
  }

  node_config {
    machine_type    = var.node_machine_type
    service_account = google_service_account.node.email

    workload_metadata_config {
      mode = "GKE_METADATA"
    }

    shielded_instance_config {
      enable_secure_boot          = true
      enable_integrity_monitoring = true
    }

    # Minimal, purpose-specific scopes — not the broad cloud-platform scope.
    # Image pull access comes from an explicit IAM role binding
    # (../artifact-registry), not from an oauth scope.
    oauth_scopes = [
      "https://www.googleapis.com/auth/logging.write",
      "https://www.googleapis.com/auth/monitoring",
    ]

    labels = {
      "nectrix-io-node-group" = "default"
    }
  }

  management {
    auto_repair  = true
    auto_upgrade = true
  }
}

# A dedicated, minimal service account for the VM itself — not the project's
# default Compute Engine SA (which typically carries a broad Editor role).
# Granted only artifactregistry.reader (below) so k3s's containerd can pull
# images natively, no imagePullSecret needed — same pattern
# infra/terraform/gcp/modules/gke uses for its node pool's own dedicated SA.
resource "google_service_account" "vm" {
  account_id   = "${var.name}-vm"
  display_name = "nectrix dev VM (k3s node + docker-compose host)"
}

resource "google_artifact_registry_repository_iam_member" "vm_reader" {
  location   = var.artifact_registry_location
  repository = var.artifact_registry_repository_id
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.vm.email}"
}

# The legacy metadata-SSH-key flow (gcloud compute scp/ssh, not OS Login —
# see the metadata block's note below) requires whoever connects to also be
# an accepted "user" of the TARGET INSTANCE's own service account, not just
# hold compute/IAP permissions generally — a real, live gap the first time
# this ran after the OS Login revert: ci_deploy could open the IAP tunnel
# fine but gcloud refused to inject its SSH key with "The user does not have
# access to service account 'nectrix-dev-vm@...'".
resource "google_service_account_iam_member" "ci_deploy_can_use_vm_sa" {
  service_account_id = google_service_account.vm.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${var.ci_deploy_service_account_email}"
}

resource "google_compute_address" "dev" {
  name   = "${var.name}-static-ip"
  region = var.region
}

# cloud-platform is the outer OAuth-scope boundary; the real access gate is
# the dedicated SA's own IAM bindings (artifactregistry.reader only, above) —
# same modern "broad scope + narrow IAM role" pattern
# infra/terraform/gcp/modules/gke uses for its node pool's SA, not a legacy
# wide-open default-SA setup.
resource "google_compute_instance" "dev" {
  name         = var.name
  machine_type = var.machine_type
  zone         = var.zone
  tags         = ["nectrix-dev-vm"]

  boot_disk {
    initialize_params {
      # Debian, not Ubuntu — lighter base image, and GCP's own first-party
      # image family (google_compute_image's default project is
      # debian-cloud). CentOS was the other option on the table but CentOS
      # Linux itself is EOL (Dec 2021) and CentOS Stream is a rolling
      # release — neither fits a box meant to sit still for months.
      image = "debian-cloud/debian-12"
      size  = var.boot_disk_size_gb
      type  = "pd-balanced"
    }
  }

  network_interface {
    network = "default"
    access_config {
      nat_ip = google_compute_address.dev.address
    }
  }

  service_account {
    email  = google_service_account.vm.email
    scopes = ["cloud-platform"]
  }

  metadata_startup_script = file("${path.module}/startup.sh")

  metadata = {
    # Only this instance's own SSH keys apply — a project-wide key wouldn't
    # implicitly grant access to this box too.
    block-project-ssh-keys = "true"
    # NOT OS Login — tried it first (Google's own recommended pattern for
    # CI/CD SSH access, no key management needed), but it's a real dead end
    # here: `gcloud compute scp` crashes ("gcloud crashed (TypeError):
    # quote_from_bytes() expected bytes") specifically when OS Login is
    # enabled, reproduced consistently on the GitHub Actions runner's bundled
    # Cloud SDK version (worked fine with a newer local Cloud SDK, so this is
    # a version-specific gcloud bug, not a config mistake) — for BOTH
    # recursive and single-file transfers, so there's no scp-shape
    # workaround, only avoiding OS Login entirely. Falls back to the legacy
    # metadata-SSH-key flow instead (ci_deploy granted a minimal custom role
    # for just compute.instances.setMetadata — see
    # modules/artifact-registry — rather than OS Login's IAM roles).
  }

  shielded_instance_config {
    enable_secure_boot          = true
    enable_vtpm                 = true
    enable_integrity_monitoring = true
  }

  lifecycle {
    # A startup-script or machine-type change shouldn't silently destroy the
    # box (and everything docker-compose/k3s is holding on its disk) without
    # an explicit, reviewed `terraform apply` diff being read first.
    prevent_destroy = true
  }
}

# 80/443 for Traefik (the single reverse-proxy entrypoint — see
# deploy/overlays/dev). No other public ports.
resource "google_compute_firewall" "allow_web" {
  name    = "${var.name}-allow-web"
  network = "default"

  allow {
    protocol = "tcp"
    ports    = ["80", "443"]
  }

  source_ranges = ["0.0.0.0/0"]
  target_tags   = ["nectrix-dev-vm"]
}

# IAP TCP forwarding range only — no public port 22, and no public 5432.
# Port 22 is `gcloud compute ssh/scp --tunnel-through-iap` (requires
# roles/iap.tunnelResourceAccessor); 5432 is db-migration-dev's tunnel
# straight to the docker-compose Postgres (see main-pipeline.yml) — a real,
# live gap the first time that job ran for real: only 22 was open here, so
# `gcloud compute start-iap-tunnel ... 5432` connected but the tunnel itself
# never came up, and the job's own wait-loop had no failure check to catch
# it (it just moved on and Liquibase hit a bare "connection refused" later).
resource "google_compute_firewall" "allow_iap" {
  name    = "${var.name}-allow-iap"
  network = "default"

  allow {
    protocol = "tcp"
    ports    = ["22", "5432"]
  }

  source_ranges = var.ssh_source_ranges
  target_tags   = ["nectrix-dev-vm"]
}

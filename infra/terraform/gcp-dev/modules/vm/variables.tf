variable "name" {
  type    = string
  default = "nectrix-dev"
}

variable "region" {
  type = string
}

variable "zone" {
  type = string
}

variable "machine_type" {
  type = string
}

variable "boot_disk_size_gb" {
  type = number
}

variable "ssh_source_ranges" {
  type = list(string)
}

variable "artifact_registry_repository_id" {
  description = "Grants this VM's service account artifactregistry.reader on this repo so k3s can pull natively — no imagePullSecret needed"
  type        = string
}

variable "artifact_registry_location" {
  type = string
}

variable "ci_deploy_service_account_email" {
  description = "Granted roles/iam.serviceAccountUser on this VM's own service account — the legacy metadata-SSH-key flow (gcloud compute scp/ssh, see main.tf's metadata block note on why not OS Login) requires it on top of iap.tunnelResourceAccessor/compute.viewer"
  type        = string
}

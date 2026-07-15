variable "project_id" {
  description = "GCP project ID to deploy into — a real project with the $300/3-month credit attached"
  type        = string
}

variable "region" {
  type    = string
  default = "us-central1"
}

variable "zone" {
  description = "Compute zone within region for the VM"
  type        = string
  default     = "us-central1-a"
}

variable "machine_type" {
  description = "e2-standard-4 (4 vCPU/16GB) by default — the recurring cost driver against the $300/3-month credit, see infra/terraform/gcp-dev/README.md"
  type        = string
  default     = "e2-standard-4"
}

variable "boot_disk_size_gb" {
  type    = number
  default = 100
}

variable "domain" {
  description = "The paid apex domain (nectrix.dev) — this module only creates a delegated child zone for dev_subdomain, never touches the apex zone's own records"
  type        = string
  default     = "nectrix.dev"
}

variable "dev_subdomain" {
  description = "The delegated subtree this module owns end-to-end, e.g. \"dev\" -> dev.nectrix.dev"
  type        = string
  default     = "dev"
}

variable "github_repo" {
  description = "GitHub repo allowed to push images / deploy via Workload Identity Federation (org/repo)"
  type        = string
  default     = "avison9/nectrix"
}

variable "ssh_source_ranges" {
  description = "CIDR ranges allowed to reach the VM's SSH port — restricted to Google's IAP TCP-forwarding range by default (no public SSH exposure), not the whole internet"
  type        = list(string)
  default     = ["35.235.240.0/20"]
}

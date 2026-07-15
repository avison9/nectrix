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

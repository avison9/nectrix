variable "environment" {
  description = "Environment name — must match the selected terraform workspace (enforced by the check block in main.tf)"
  type        = string

  validation {
    condition     = contains(["dev", "staging", "production"], var.environment)
    error_message = "environment must be one of: dev, staging, production."
  }
}

variable "project_id" {
  description = "GCP project ID to deploy into"
  type        = string
}

variable "region" {
  type    = string
  default = "us-east1"
}

variable "vpc_cidr" {
  type = string
}

variable "pods_cidr" {
  type = string
}

variable "services_cidr" {
  type = string
}

variable "gke_cluster_version" {
  type    = string
  default = "1.31"
}

variable "node_machine_type" {
  type = string
}

variable "node_min_count" {
  type = number
}

variable "node_max_count" {
  type = number
}

variable "node_initial_count" {
  type = number
}

variable "cloudsql_tier" {
  type = string
}

variable "cloudsql_disk_size_gb" {
  type    = number
  default = 100
}

variable "redis_memory_size_gb" {
  type    = number
  default = 4
}

variable "redis_tier" {
  type    = string
  default = "STANDARD_HA"
}

variable "kafka_vcpu_count" {
  type    = number
  default = 3
}

variable "kafka_memory_bytes" {
  type    = number
  default = 3221225472 # 3 GiB
}

variable "gcs_bucket_name" {
  description = "Globally-unique GCS bucket name for this environment's object storage"
  type        = string
}

variable "github_repo" {
  description = "GitHub repo allowed to push images via Workload Identity Federation (org/repo) — see modules/artifact-registry"
  type        = string
  default     = "avison9/nectrix"
}

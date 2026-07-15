variable "name_prefix" {
  type = string
}

variable "project_id" {
  type = string
}

variable "region" {
  type = string
}

variable "github_repo" {
  description = "GitHub repo allowed to push/deploy via Workload Identity Federation, as org/repo"
  type        = string
  default     = "avison9/nectrix"
}

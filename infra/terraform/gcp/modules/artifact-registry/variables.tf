variable "name_prefix" {
  type = string
}

variable "region" {
  type = string
}

variable "github_repo" {
  description = "GitHub repo allowed to push via Workload Identity Federation, as org/repo"
  type        = string
  default     = "avison9/nectrix"
}

variable "gke_node_service_account_email" {
  description = "The GKE node pool's service account — granted artifactregistry.reader so nodes can pull without a Kubernetes imagePullSecret"
  type        = string
}

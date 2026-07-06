variable "name_prefix" {
  type = string
}

variable "region" {
  type = string
}

variable "cluster_version" {
  description = "GKE release channel minimum version (e.g. latest); left flexible via release_channel instead of a pinned master version"
  type        = string
  default     = "1.31"
}

variable "network_self_link" {
  type = string
}

variable "subnetwork_self_link" {
  type = string
}

variable "pods_range_name" {
  type = string
}

variable "services_range_name" {
  type = string
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

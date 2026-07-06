variable "name_prefix" {
  type = string
}

variable "project_id" {
  type = string
}

variable "region" {
  type = string
}

variable "vpc_cidr" {
  description = "Primary CIDR for the GKE node subnet (a /20 recommended)"
  type        = string
}

variable "pods_cidr" {
  description = "Secondary range CIDR for GKE pod IPs"
  type        = string
}

variable "services_cidr" {
  description = "Secondary range CIDR for GKE service IPs"
  type        = string
}

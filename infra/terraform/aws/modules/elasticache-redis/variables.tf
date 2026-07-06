variable "name_prefix" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "vpc_cidr" {
  description = "VPC CIDR, for the DNS egress rule (see main.tf's aws_security_group.this)"
  type        = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "allowed_security_group_id" {
  description = "Security group (typically the EKS cluster SG) allowed to reach Redis on 6379"
  type        = string
}

variable "node_type" {
  type = string
}

variable "num_cache_clusters" {
  description = "Number of nodes in the replication group (>=2 enables automatic failover)"
  type        = number
  default     = 2
}

variable "engine_version" {
  type    = string
  default = "7.1"
}

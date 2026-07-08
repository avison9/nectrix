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

variable "num_node_groups" {
  description = "Shard count (TICKET-008 real Redis Cluster mode)"
  type        = number
  default     = 2
}

variable "replicas_per_node_group" {
  description = "Replica count per shard (>=1 enables automatic failover for that shard)"
  type        = number
  default     = 1
}

variable "engine_version" {
  type    = string
  default = "7.1"
}
